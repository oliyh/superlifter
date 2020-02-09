# superlifter

[Urania](https://github.com/funcool/urania) is a remote data access library for Clojure/script. It provides batching of similar requests and deduplication via caching.

What's missing? Smooth integration with libraries like [lacinia](https://github.com/walmartlabs/lacinia), where GraphQL resolvers are run independantly and must return data (or promises of data), leading to 1+n problems which can only be resolved by prefetching which complicates code.

The aim of superlifter is to provide a way of combining fetches delineated by time buckets, thresholds or explicit trigger rather than by node resolution.

As the underlying fetches are performed by Urania, knowledge of this library is required (it's very simple, though!).

Superlifter provides the following features:

- Fast, simple implementation of DataLoader pattern
- Bucketing by time or by queue size
- Supports asynchronous fetching
- Exposes protocol to combine multiple fetches into a single fetch
- Shared cache for all fetches in a session
  - Guarantees consistent results
  - Avoids duplicating work

[![Clojars Project](https://img.shields.io/clojars/v/superlifter.svg)](https://clojars.org/superlifter)

## Vanilla usage

Start a superlifter as follows:

```clj
(require '[superlifter.core :as s])
(require '[urania.core :as u])

(def context (s/start! {:buckets {:default {:triggers {}}))
```

This superlifter has no triggers, and must be fetched manually.
Other kinds of trigger include `queue-size` and `interval` (like DataLoader), detailed below.
Remember to call `(s/stop! context)` when you have finished using it.

You can enqueue items for fetching:

```clj
(def hello-promise (s/enqueue! context (u/value "Hello world")))
```

When the fetch is triggered the promises will be delivered.

### Triggering fetches

Regardless of the trigger used, you can always manually trigger a fetch of whatever is currently in the queue using `(s/fetch! context)`.
This returns a promise which is delivered when all the fetches in the queue are complete, containing the results of all the fetches.

#### On demand

In the example above no triggers were specified. Fetches will only happen when you call `(s/fetch! context)`.

#### Queue size trigger

You can specify that the queue is fetched when the queue reaches a certain size. You can configure this to e.g. 10 using the following options:
```clj
{:triggers {:queue-size {:threshold 10}}}
```

#### Interval trigger
You can specify that the queue is fetched every e.g. 100ms using the following options:
```clj
{:triggers {:interval {:interval 100}}}
```

This will give batching by time in a similar fashion to DataLoader.

#### Your own trigger

You can register your own kind of trigger by participating the in `s/start-trigger!` multimethod, so you can listen for other kinds of events that might let you know when it's a good time to perform the fetch.
See the interval trigger implementation for inspiration.

#### Trigger combinations
You can supply any number of triggers which will all run concurrently and the queue will be fetched when any one condition is met.

```clj
{:triggers {:queue-size {:threshold 10}
            :interval {:interval 100}}}
```

## Lacinia usage

Given the following schema in lacinia:

```clj
{:objects {:PetDetails {:fields {:name {:type 'String}
                                 :age {:type 'Int}}}
           :Pet {:fields {:id {:type 'String}
                          :details {:type :PetDetails
                                    :resolve resolve-pet-details}}}}
 :queries {:pets
           {:type '(list :Pet)
            :resolve resolve-pets}}}
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
(require '[superlifter.core :as s])
(require '[urania.core :as u])
(require '[promesa.core :as prom])

;; urania muses to perform fetch operations

(defrecord FetchPets []
  u/DataSource
  (-identity [this] :fetch-pets)
  (-fetch [this env]
    (prom/create (fn [resolve reject]
                   (resolve (map (fn [id]
                                   {:id id})
                                 (keys (:db env))))))))

(defrecord FetchPet [id]
  u/DataSource
  (-identity [this] id)
  (-fetch [this env]
    (log/info "Fetching pet details" id)
    (prom/create (fn [resolve reject]
                   (resolve (get (:db env) id)))))

  u/BatchedSource
  (-fetch-multi [muse muses env]
    (let [muses (cons muse muses)
          pet-ids (map :id muses)]
      (log/info "Combining request for ids" pet-ids)
      (zipmap (map u/-identity muses)
              (map (:db env) pet-ids)))))

;; resolvers

(defn- resolve-pets [context args parent]
  (let [superlifter (get-in context [:request :superlifter])]
    (-> (sl/enqueue! superlifter (->FetchPets))
        (sl/then-add-bucket! superlifter
                             :pet-details
                             (fn [pet-ids]
                               {:triggers {:queue-size {:threshold (count pet-ids)}
                                           :interval {:interval 50}}}))
        ->lacinia-promise)))

(defn- resolve-pet-details [context args {:keys [id]}]
  (-> (sl/enqueue! (get-in context [:request :superlifter]) :pet-details (->FetchPet id))
      (->lacinia-promise)))
```

## Build
[![CircleCI](https://circleci.com/gh/oliyh/superlifter.svg?style=svg)](https://circleci.com/gh/oliyh/superlifter)

## License

Copyright © 2019 oliyh

Distributed under the Eclipse Public License either version 1.0 or (at
your option) any later version.
