(ns example.integration-test
  (:require [example.server :refer [service]]
            [io.pedestal.http :as server]
            [clj-http.client :as http]
            [clojure.test :refer [deftest testing is use-fixtures]]))

(defn- with-server [f]
  (let [server (-> service
                   (assoc ::server/port 8899)
                   server/create-server
                   server/start)]
    (try (f)
         (finally (server/stop server)))))

(use-fixtures :once with-server)

(deftest simple-test
  (testing "single pet"
    (is (= {:data
            {:pet
             {:id "abc-123"
              :details {:age 11, :name "Lyra"}}}}
           (:body (http/post "http://localhost:8899/graphql"
                             {:form-params {:query "{ pet (id: \"abc-123\") { id details { name age } } }"}
                              :content-type :json
                              :as :json})))))

  (testing "multiple pets"
    (is (= {:data
            {:pets
             [{:id "abc-123",
               :details {:age 11, :name "Lyra"}}
              {:id "def-234",
               :details {:age 11, :name "Pantalaimon"}}
              {:id "ghi-345",
               :details {:age 41, :name "Iorek"}}]}}
           (:body (http/post "http://localhost:8899/graphql"
                             {:form-params {:query "{ pets { id details { name age } } }"}
                              :content-type :json
                              :as :json}))))))

(def aliases-query
  "{
     one: pets {
       id
       details {
         age
         name
       }
       more_details: details {
         age
       }
     }

     two: pets {
       id
       details {
         age
         name
       }
       more_details: details {
         age
       }
     }
   }")

(deftest aliases-test
  (is (= {:data
          {:one
           [{:id "abc-123",
             :details {:age 11, :name "Lyra"},
             :more_details {:age 11}}
            {:id "def-234",
             :details {:age 11, :name "Pantalaimon"},
             :more_details {:age 11}}
            {:id "ghi-345",
             :details {:age 41, :name "Iorek"},
             :more_details {:age 41}}]
           :two
           [{:id "abc-123",
             :details {:age 11, :name "Lyra"},
             :more_details {:age 11}}
            {:id "def-234",
             :details {:age 11, :name "Pantalaimon"},
             :more_details {:age 11}}
            {:id "ghi-345",
             :details {:age 41, :name "Iorek"},
             :more_details {:age 41}}]}}

         (:body (http/post "http://localhost:8899/graphql"
                           {:form-params {:query aliases-query}
                            :content-type :json
                            :as :json})))))

(def asymmetric-query
  "{
     pets {
       id
       details {
         age
         name
       }
     }

     pet(id: \"abc-123\") {
       id
       details {
         age
         name
       }
     }
  }")

(deftest asymmetric-test
  (is (= {:data
          {:pets
           [{:id "abc-123",
             :details {:age 11, :name "Lyra"},}
            {:id "def-234",
             :details {:age 11, :name "Pantalaimon"},}
            {:id "ghi-345",
             :details {:age 41, :name "Iorek"},}]
           :pet
           {:id "abc-123"
            :details {:age 11, :name "Lyra"}}}}

         (:body (http/post "http://localhost:8899/graphql"
                           {:form-params {:query asymmetric-query}
                            :content-type :json
                            :as :json})))))

(deftest stress-test
  (let [n 50
        a (future (dotimes [_i n]
                    (simple-test)))
        b (future (dotimes [_i n]
                    (aliases-test)))
        c (future (dotimes [_i n]
                    (asymmetric-test)))]
    @a
    @b
    @c))
