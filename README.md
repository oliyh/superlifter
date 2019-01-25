# superlifter

[Urania](https://github.com/funcool/urania) is a remote data access library for Clojure/script. It provides batching of similar requests and deduplication via caching.

What's missing? Smooth integration with libraries like [lacinia](https://github.com/walmartlabs/lacinia), where GraphQL resolvers are run independantly and must return data (or promises of data), leading to 1+n problems which can only be resolved by prefetching which complicates code.

The aim of superlifter is to provide a way of combining fetches delineated by time buckets, thresholds or explicit trigger rather than by node resolution.

As the underlying fetches are performed by Urania, knowledge of this library is required (it's very simple, though!).

[![Clojars Project](https://img.shields.io/clojars/v/superlifter.svg)](https://clojars.org/superlifter)

## Usage

Start a superlifter as follows:

```clj
(require '[superlifter.core :as s])
(require '[urania.core :as u])

(def context (s/start! {:trigger {:kind :callback}}))
```

Other kinds of trigger include `queue-size` and `interval` (like DataLoader), detailed below.

You can enqueue items for fetching:

```clj
(def hello-promise (s/enqueue! context (u/value "Hello world")))
```

When the fetch is triggered the promises will be delivered.

### Triggering fetches
Regardless of the trigger used, you can always manually trigger a fetch of whatever is currently in the queue using `(s/fetch! context)`.
This returns a promise which is delivered when all the fetches in the queue are complete, containing the results of all the fetches.

#### Callback trigger
In the example above a callback trigger was used. The fetch will only happen when you call `(s/fetch! context)`.

#### Queue size trigger
You can specify that the queue is fetched when the queue reaches a certain size. You can configure this to e.g. 10 using the following options:
```clj
{:trigger {:kind :queue-size
           :threshold 10}}
```

During operation you can adjust the queue size for the next fetch. This is useful for solving 1+n queries because you can adjust it by n at the parent level to ensure it will not trigger before all n children are enqueued.

#### Interval trigger
You can specify that the queue is fetched every e.g. 100ms using the following options:
```clj
{:trigger {:kind :interval
           :interval 100}}
```

This will give batching by time in a similar fashion to DataLoader.

#### Your own trigger
You can register your own kind of trigger by participating the in `s/start-trigger!` multimethod, so you can listen for other kinds of events that might let you know when it's a good time to perform the fetch.
See the interval trigger implementation for inspiration.

## Lacinia example

Given the following 1+n fetch in GraphQL:

```clj
;; todo
```

We can rewrite this using superlifter:

```clj
;; todo
```

## Build
[![CircleCI](https://circleci.com/gh/oliyh/superlifter.svg?style=svg)](https://circleci.com/gh/oliyh/superlifter)

## License

Copyright © 2019 oliyh

Distributed under the Eclipse Public License either version 1.0 or (at
your option) any later version.
