(ns qbits.jet.test.server
  (:use
   clojure.test)
  (:require
   [qbits.jet.server :refer [run-jetty]]
   [qbits.jet.client.websocket :as ws]
   [qbits.jet.client.http :as http]
   [clojure.core.async :as async]
   [ring.middleware.params :as ring-params]
   [ring.middleware.keyword-params :as ring-kw-params])
  (:import
   (org.eclipse.jetty.util.thread QueuedThreadPool)
   (org.eclipse.jetty.server Server Request)
   (org.eclipse.jetty.server.handler AbstractHandler)))

(defn hello-world [request]
  {:status  200
   :headers {"Content-Type" "text/plain"}
   :body    "Hello World"})

(defn content-type-handler [content-type]
  (constantly
   {:status  200
    :headers {"Content-Type" content-type}
    :body    ""}))

(defn echo-handler [request]
  {:status 200
   :headers {"request-map" (str (dissoc request :body))}
   :body (:body request)})

(defn async-handler [request]
  (let [ch (async/chan)]
    (async/go
      (async/<! (async/timeout 1000))
      (async/>! ch
                {:body "foo"
                 :headers {"Content-Type" "foo"}
                 :status 202}))
    ch))

(defn async-handler+chunked-body [request]
  (let [ch (async/chan)]
    (async/go
      (async/<! (async/timeout 1000))
      (async/>! ch
                {:body (let [ch (async/chan)]
                         (async/go (async/>! ch "foo")
                                   (async/>! ch "bar")
                                   (async/close! ch))
                         ch)


                 :headers {"Content-Type" "foo"}
                 :status 202}))
    ch))

(defn chunked-handler [request]
  (let [ch (async/chan 1)]
    (async/go
      (dotimes [i 5]
        (async/<! (async/timeout 300))
        (async/>! ch (str i)))
      (async/close! ch))
    {:body ch
     :headers {"Content-Type" "foo"}
     :status 201}))

(defn request-map->edn
  [response]
  (-> response (get-in [:headers "request-map"]) read-string))


(defmacro with-server [app options & body]
  `(let [server# (run-jetty (-> ~(or app (fn [_]))
                                ring-kw-params/wrap-keyword-params
                                ring-params/wrap-params)
                            ~(assoc options :join? false))]
     (try
       ~@body
       (finally (.stop server#)))))

(deftest test-run-jetty
  (testing "HTTP server"
    (with-server hello-world {:port 4347}
      (let [response (async/<!! (http/get "http://localhost:4347"))]
        (is (= (:status response) 200))
        (is (.startsWith (get-in response [:headers "content-type"])
                         "text/plain"))
        (is (= (async/<!! (:body response)) "Hello World")))))

  (testing "HTTPS server"
    (with-server hello-world {:port 4347
                              :ssl-port 4348
                              :keystore "test/keystore.jks"
                              :key-password "password"}
      (let [response (async/<!! (http/get "https://localhost:4348" {:insecure? true}))]
        (is (= (:status response) 200))
        (is (= (-> response :body async/<!!) "Hello World")))))

  (testing "setting daemon threads"
    (testing "default (daemon off)"
      (let [server (run-jetty hello-world {:port 4347 :join? false})]
        (is (not (.. server getThreadPool isDaemon)))
        (.stop server)))
    (testing "daemon on"
      (let [server (run-jetty hello-world {:port 4347 :join? false :daemon? true})]
        (is (.. server getThreadPool isDaemon))
        (.stop server)))
    (testing "daemon off"
      (let [server (run-jetty hello-world {:port 4347 :join? false :daemon? false})]
        (is (not (.. server getThreadPool isDaemon)))
        (.stop server))))

   (testing "setting min-threads"
    (let [server (run-jetty hello-world {:port 4347
                                         :min-threads 3
                                         :join? false})
          thread-pool (. server getThreadPool)]
      (is (= 3 (. thread-pool getMinThreads)))
      (.stop server)))

  (testing "default min-threads"
    (let [server (run-jetty hello-world {:port 4347
                                         :join? false})
          thread-pool (. server getThreadPool)]
      (is (= 8 (. thread-pool getMinThreads)))
      (.stop server)))

  (testing "default character encoding"
    (with-server (content-type-handler "text/plain") {:port 4347}
      (let [response (async/<!! (http/get "http://localhost:4347"))]
        (is (.contains
             (get-in response [:headers "content-type"])
             "text/plain")))))

  (testing "custom content-type"
    (with-server (content-type-handler "text/plain;charset=UTF-16;version=1") {:port 4347}
      (let [response (async/<!! (http/get "http://localhost:4347"))]
        (is (= (get-in response [:headers "content-type"])
               "text/plain;charset=UTF-16;version=1")))))

  (testing "request translation"
    (with-server echo-handler {:port 4347}
      (let [response (async/<!! (http/post "http://localhost:4347/foo/bar/baz?surname=jones&age=123" {:body "hello"}))]
        (is (= (:status response) 200))
        (is (= (-> response :body async/<!!) "hello"))
        (let [request-map (request-map->edn response)]
          (is (= (:query-string request-map) "surname=jones&age=123"))
          (is (= (:uri request-map) "/foo/bar/baz"))
          (is (= (:content-length request-map) 5))
          (is (= (:character-encoding request-map) "UTF-8"))
          (is (= (:request-method request-map) :post))
          (is (= (:content-type request-map) "text/plain;charset=UTF-8"))
          (is (= (:remote-addr request-map) "127.0.0.1"))
          (is (= (:scheme request-map) :http))
          (is (= (:server-name request-map) "localhost"))
          (is (= (:server-port request-map) 4347))
          (is (= (:ssl-client-cert request-map) nil))))))


  (testing "chunked response"
    (with-server chunked-handler {:port 4347}
      (let [response (async/<!! (http/get "http://localhost:4347/"))]
        (is (= (:status response) 201))
        (is (= (-> response :body async/<!!) "0"))
        (is (= (-> response :body async/<!!) "1"))
        (is (= (-> response :body async/<!!) "2"))
        (is (= (-> response :body async/<!!) "3"))
        (is (= (-> response :body async/<!!) "4"))
        (is (= (-> response :body async/<!!) nil)))))

  (testing "async response"
    (with-server async-handler {:port 4347}
      (let [response (async/<!! (http/get "http://localhost:4347/"))]
        (is (= (:status response) 202)))))

  (testing "async+chunked-body"
    (with-server async-handler+chunked-body {:port 4347}
      (let [response (async/<!! (http/get "http://localhost:4347/"))
            body (:body response)]
        (is (= (:status response) 202))
        (is (= "foo" (async/<!! body)))
        (is (= "bar" (async/<!! body))))))

 (testing "POST+PUT requests"
    (with-server echo-handler
      {:port 4347}
      (let [response (async/<!! (http/post "http://localhost:4347"
                                           {:form-params {:foo "bar"}}))
            request-map (request-map->edn response)]
        ;; TODO post files
        (is (= "bar" (get-in request-map [:form-params "foo"]))))

      (let [response (async/<!! (http/put "http://localhost:4347"
                                          {:form-params {:foo "bar"}}))
            request-map (request-map->edn response)]
        (is (= "bar" (get-in request-map [:form-params "foo"]))))))

 (testing "HEAD+DELETE+TRACE requests"
    (with-server echo-handler
      {:port 4347}
      (let [response (async/<!! (http/head "http://localhost:4347"))
            request-map (request-map->edn response)]
        (is (= :head (:request-method request-map))))
      (let [response (async/<!! (http/delete "http://localhost:4347"))
            request-map (request-map->edn response)]
        (is (= :delete (:request-method request-map))))
      (let [response (async/<!! (http/trace "http://localhost:4347"))
            request-map (request-map->edn response)]
        (is (= :trace (:request-method request-map))))))


 (testing "HTTP request :as"
   (is (= "zuck" (-> (http/get "http://graph.facebook.com/zuck" {:as :json})
                     async/<!! :body async/<!! :username)))

   (is (= "zuck" (-> (http/get "http://graph.facebook.com/zuck" {:as :json-str})
                     async/<!! :body async/<!! (get "username")))))

 (testing "cookies"
   (with-server echo-handler
     {:port 4347}
     (is (= "foo=bar; something=else"
            (get-in (-> (http/get "http://localhost:4347"
                                  {:cookies [{:name "foo" :value "bar" :max-age 5}
                                             {:name "something" :value "else" :max-age 5}]})
                        async/<!!
                        request-map->edn)
                    [:headers "cookie"])))))

 (testing "standalone client"
   (with-server echo-handler
     {:port 4347}
     (let [c (http/client)]
       (is (= 200 (:status (async/<!! (http/get c {:url "http://localhost:4347"}))))))))

 (testing "Auth tests"
   (let [u "test-user"
         pwd "test-pwd"]
     ;; (-> (http/get (format "https://httpbin.org/digest-auth/auth/%s/%s"
     ;;                                  u pwd)
     ;;                          {:auth {:type :digest :user u :password pwd :realm "me@kennethreitz.com"}})
     ;;                async/<!! prn)
     (is (= 200 (-> (http/get (format "http://httpbin.org/basic-auth/%s/%s"
                                      u pwd)
                              {:auth {:type :basic :user u :password pwd :realm "Fake Realm"}})
                    async/<!! :status)))

     (is (= 200 (-> (http/get (format "https://httpbin.org/basic-auth/%s/%s"
                                      u pwd)
                              {:auth {:type :basic :user u :password pwd :realm "Fake Realm"}})
                    async/<!! :status)))

     ;; (is (= 200 (-> (http/get (format "https://httpbin.org/digest-auth/auth/%s/%s"
     ;;                                  u pwd)
     ;;                          {:digest-auth {:user u :password pwd :realm "me@kennethreitz.com"}})
     ;;                async/<!! :status)))
     ))

  (testing "WebSocket ping-pong"
    (let [p (promise)]
      (with-server nil
        {:port 4347
         :websocket-handler
         (fn [{:keys [in out ctrl] :as request}]
           (clojure.pprint/pprint request)
           (async/go
             (when (= "PING" (async/<! in))
               (async/>! out "PONG"))))}
        (ws/connect! "ws://0.0.0.0:4347/app?foo=bar"
                     (fn [{:keys [in out ctrl]}]
                       (async/go
                         (async/>! out "PING")
                         (when (= "PONG" (async/<! in))
                           (async/close! out)
                           (deliver p true)))))
        (is (deref p 1000 false)))))

  (testing "content-type encoding"
    (is (= "Content-Type: application/json" (http/encode-content-type :application/json)))
    (is (= "Content-Type: application/json; charset=UTF-8" (http/encode-content-type [:application/json "UTF-8"])))
    (is (= "Content-Type: application/json" (http/encode-content-type "application/json")))
    (is (= "Content-Type: application/json; charset=UTF-8" (http/encode-content-type ["application/json" "UTF-8"])))))




;; (run-tests)
