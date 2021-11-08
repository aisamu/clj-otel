(ns example.manual-instrument.middleware.puzzle-service
  "Example application demonstrating using `clj-otel` to add telemetry to a
  synchronous Ring HTTP service that is run without the OpenTelemetry
  instrumentation agent."
  (:require [clj-http.client :as client]
            [clojure.string :as str]
            [ring.adapter.jetty :as jetty]
            [ring.middleware.params :as params]
            [ring.util.response :as response]
            [steffan-westcott.otel.api.trace.http :as trace-http]
            [steffan-westcott.otel.api.trace.span :as span]
            [steffan-westcott.otel.context :as context]))


(defn client-request
  "Perform a synchronous HTTP request using `clj-http`."
  [request]

  ;; Wrap the synchronous body in a new client span.
  (span/with-span! (trace-http/client-span-opts request)

    (let [;; Propagate context containing client span to remote
          ;; server by injecting headers. This enables span
          ;; correlation to make distributed traces.
          request' (update request :headers merge (context/->headers))

          response (client/request request')]

      ;; Add HTTP response data to the client span.
      (trace-http/add-response-data! response)

      response)))



(defn get-random-word
  "Get a random word string of the requested type."
  [word-type]
  (let [response (client-request {:method           :get
                                  :url              "http://localhost:8081/random-word"
                                  :query-params     {"type" (name word-type)}
                                  :throw-exceptions false})]
    (if (= (:status response) 200)
      (:body response)
      (throw (ex-info "random-word-service failed"
                      {:server-status (:status response)})))))



(defn random-words
  "Get random words of the requested types."
  [word-types]

  ;; Wrap the synchronous body in a new internal span.
  (span/with-span! {:name       "Getting random words"
                    :attributes {:word-types word-types}}

    ;; Use `doall` to force lazy sequence to be realized within span
    (doall (map get-random-word word-types))))



(defn scramble
  "Scrambles a given word."
  [word]
  (span/with-span! {:name       "Scrambling word"
                    :attributes {:word word}}

    (Thread/sleep 5)
    (let [scrambled-word (->> word seq shuffle (apply str))]

      ;; Add more attributes to internal span
      (span/add-span-data! {:attributes {:scrambled scrambled-word}})

      scrambled-word)))



(defn generate-puzzle
  "Constructs a puzzle string containing scrambled random words of the
  requested word types."
  [word-types]
  (let [words (random-words word-types)
        scrambled-words (map scramble words)]

    ;; Add event to span
    (span/add-span-data! {:event {:name       "Completed setting puzzle"
                                  :attributes {:puzzle scrambled-words}}})

    (str/join " " scrambled-words)))



(defn get-puzzle-handler
  "Synchronous Ring handler for `GET /puzzle` request. Returns an HTTP
  response containing a puzzle of the requested word types."
  [{:keys [query-params]}]

  ;; Add data describing matched route to server span.
  (trace-http/add-route-data! "/puzzle")

  (let [word-types (map keyword (str/split (get query-params "types") #","))
        puzzle (generate-puzzle word-types)]
    (response/response puzzle)))



(defn handler
  "Synchronous Ring handler for all requests."
  [{:keys [request-method uri] :as request}]
  (case [request-method uri]
    [:get "/puzzle"] (get-puzzle-handler request)
    (response/not-found "Not found")))



(def service
  "Ring handler with middleware applied."
  (-> handler
      params/wrap-params

      ;; Wrap request handling of all routes. As this application is not run
      ;; with the OpenTelemetry instrumentation agent, create a server span
      ;; for each request.
      (trace-http/wrap-server-span {:create-span? true
                                    :server-name  "puzzle"})))



(defn init-tracer!
  "Set default tracer used when manually creating spans."
  []
  (let [tracer (span/get-tracer {:name "puzzle-service" :version "1.0.0"})]
    (span/set-default-tracer! tracer)))


;;;;;;;;;;;;;


(init-tracer!)
(defonce server (jetty/run-jetty #'service {:port 8080 :join? false}))

(comment

  )