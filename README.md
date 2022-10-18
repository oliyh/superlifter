# superlifter

Superlifter is an implementation of [DataLoader](https://github.com/graphql/dataloader) for Clojure.

To quote from the DataLoader readme:

> DataLoader allows you to decouple unrelated parts of your application without sacrificing the performance of batch data-loading. While the loader presents an API that loads individual values, all concurrent requests will be coalesced and presented to your batch loading function. This allows your application to safely distribute data fetching requirements throughout your application and maintain minimal outgoing data requests.

Superlifter uses [Urania](https://github.com/funcool/urania), a remote data access library for Clojure/script inspired by [Haxl](https://github.com/facebook/Haxl)
which in turn inspired DataLoader. Urania allows batching of similar fetches and deduplication via caching of identical fetches.

Superlifter adds smooth integration with libraries like [lacinia](https://github.com/walmartlabs/lacinia), where GraphQL resolvers are run independently
and must return data (or promises of data), leading to 1+n problems which can otherwise only be resolved by prefetching which complicates code.

The aim of superlifter is to provide a way of combining fetches delineated by time buckets, thresholds or explicit trigger rather than by node resolution.

As the underlying fetches are performed by Urania, knowledge of this library is required (it's very simple, though!).

Superlifter provides the following features:

- Fast, simple implementation of DataLoader pattern
- Bucketing by time or by queue size
- Asynchronous fetching
- Batching of fetches
- Shared cache for all fetches in a session
  - Guarantees consistent results
  - Avoids duplicating work
- Access to the cache allows longer-term persistence

[![Clojars Project](https://img.shields.io/clojars/v/superlifter.svg)](https://clojars.org/superlifter)

- [Vanilla usage](#vanilla-usage)
- [Lacinia usage](#lacinia-usage)

## Vanilla usage

Start a superlifter as follows:

```clj
(require '[superlifter.api :as s])
(require '[urania.core :as u])

(def context (s/start! {:buckets {:default {:triggers {}}}}))
```

This superlifter has no triggers, and must be fetched manually.
Other kinds of trigger include `queue-size` and `interval` (like DataLoader), detailed below.
Remember to call `(s/stop! context)` when you have finished using it.

You can enqueue items for fetching:

```clj
(s/with-superlifter context
  (def hello-promise (s/enqueue! (u/value "Hello world"))))
```

When the fetch is triggered the promises will be delivered.

### Triggering fetches

Regardless of the trigger used, you can always manually trigger a fetch of whatever is currently in the queue using
`(s/with-superlifter context (s/fetch!))`.
This returns a promise which is delivered when all the fetches in the queue are complete, containing the results of all the fetches.

#### On demand

In the example above no triggers were specified. Fetches will only happen when you call
`(s/with-superlifter context (s/fetch!))`.

#### Queue size trigger

You can specify that the queue is fetched when the queue reaches a certain size. You can configure this to e.g. 10 using the following options:
```clj
{:triggers {:queue-size {:threshold 10}}}
```

#### Elastic trigger

You can specify that the queue is fetched when the queue size exceeds the threshold. The threshold can be updated dynamically and snaps
back to zero when a fetch is performed, in contrast to the queue size trigger which remains at a fixed size. The trigger can be specified as follows:
```clj
{:triggers {:elastic {:threshold 0}}}
```

#### Interval trigger
You can specify that the queue is fetched every e.g. 100ms using the following options:
```clj
{:triggers {:interval {:interval 100}}}
```

This will give batching by time in a similar fashion to DataLoader.

#### Debounced trigger
You can specify that the queue is fetched when no items have been added within the last e.g. 100ms with these options
```clj
{:triggers {:debounced {:interval 100}}}
```

#### Your own trigger

You can register your own kind of trigger by participating the in `s/start-trigger!` multimethod, so you can listen for other kinds of events that might let you know when it's a good time to perform the fetch.
See the interval trigger implementation for inspiration.

#### Trigger combinations
You can supply any number of triggers which will all run concurrently and the queue will be fetched when any one condition is met.

```clj
{:triggers {:queue-size {:threshold 10}
            :interval {:interval 100}}}
```

It is recommended that a `:queue-size` trigger is always used in combination with an `:interval` or `debounced` trigger in order to avoid
hanging when you have e.g. a queue size of 5 but only four muses are enqueued within it.

## Lacinia usage

Given the following schema in lacinia:

```clj
(def schema
 {:objects {:PetDetails {:fields {:name {:type 'String}
                                  :age {:type 'Int}}}
            :Pet {:fields {:id {:type 'String}
                           :details {:type :PetDetails
                                     :resolve resolve-pet-details}}}}
  :queries {:pets
            {:type '(list :Pet)
             :resolve resolve-pets}}})
```

Where the resolvers are as follows:

```clj
(defn- resolve-pets [context args parent]
  (let [ids (keys (:db context))]
    (map (fn [id] {:id id}) ids)))

;; invoked n times, once for every id from the parent resolver
(defn- resolve-pet-details [context args {:keys [id]}]
  (get-in (:db context) id))
```

We can rewrite this using superlifter (see the [example code](https://github.com/oliyh/superlifter/tree/master/example) for full context):

```clj
;; superlifter.lacinia has a different `with-superlifter` macro
;; to help you return a lacinia promise
(require '[superlifter.lacinia :refer [with-superlifter]])
(require '[clojure.tools.logging :as log])

;; def-fetcher - a convenience macro like defrecord for things which cannot be combined
(s/def-fetcher FetchPets []
  (fn [_this env]
    (map (fn [id] {:id id}) (keys (:db env)))))

;; def-superfetcher - a convenience macro like defrecord for combinable things
(s/def-superfetcher FetchPet [id]
  (fn [many env]
    (log/info "Combining request for" (count many) "pets")
    (map (:db env) (map :id many))))

(defn- resolve-pets [context _args _parent]
  (with-superlifter context
    (-> (s/enqueue! (->FetchPets))
        (s/update-trigger! :pet-details :elastic
                           (fn [trigger-opts pet-ids]
                             (update trigger-opts :threshold + (count pet-ids)))))))

(defn- resolve-pet-details [context _args {:keys [id]}]
  (with-superlifter context
    (s/enqueue! :pet-details (->FetchPet id))))
```

It's usual to start a Superlifter before each query and stop it afterwards.
There is an `inject-superlifter` interceptor which will help you do this:

```clj
(require '[com.walmartlabs.lacinia.pedestal :as lacinia])
(require '[com.walmartlabs.lacinia.schema :as schema])
(require '[superlifter.lacinia :refer [inject-superlifter]])

(def pet-db (atom {"abc-123" {:name "Lyra"
                              :age 11}
                   "def-234" {:name "Pantalaimon"
                              :age 11}
                   "ghi-345" {:name "Iorek"
                              :age 41}}))

(def lacinia-opts {:graphiql true})

(def superlifter-args
  {:buckets {:default {:triggers {:queue-size {:threshold 1}}}
             :pet-details {:triggers {:elastic {:threshold 0}}}}
   :urania-opts {:env {:db @pet-db}}})

(def service
  (lacinia/service-map
   (fn [] (schema/compile schema))
   (assoc lacinia-opts
          :interceptors (into [(inject-superlifter superlifter-args)]
                              (lacinia/default-interceptors (fn [] (schema/compile schema)) lacinia-opts)))))
```

## Development

`cider-jack-in-clj&cljs` and open the test page http://localhost:9500/figwheel-extra-main/auto-testing

## Build
[![CircleCI](https://circleci.com/gh/oliyh/superlifter.svg?style=svg)](https://circleci.com/gh/oliyh/superlifter)

## Sponsorship
Development and maintenance is generously sponsored by @toyokumo

## License

Copyright © 2019 oliyh

Distributed under the Eclipse Public License either version 1.0 or (at
your option) any later version.
