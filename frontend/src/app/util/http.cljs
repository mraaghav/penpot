;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 UXBOX Labs SL

(ns app.util.http
  "A http client with rx streams interface."
  (:refer-clojure :exclude [get])
  (:require
   [app.config :as cfg]
   [app.util.object :as obj]
   [app.util.transit :as t]
   [beicon.core :as rx]
   [cljs.core :as c]
   [clojure.string :as str]
   [goog.events :as events])
  (:import
   [goog.net ErrorCode EventType]
   [goog.net.XhrIo ResponseType]
   [goog.net XhrIo]
   [goog.Uri QueryData]
   [goog Uri]))

(defn translate-method
  [method]
  (case method
    :head    "HEAD"
    :options "OPTIONS"
    :get     "GET"
    :post    "POST"
    :put     "PUT"
    :patch   "PATCH"
    :delete  "DELETE"
    :trace   "TRACE"))

(defn- normalize-headers
  [headers]
  (reduce-kv (fn [acc k v]
               (assoc acc (str/lower-case k) v))
             {} (js->clj headers)))

(defn- translate-error-code
  [code]
  (condp = code
    ErrorCode.TIMEOUT    :timeout
    ErrorCode.EXCEPTION  :exception
    ErrorCode.HTTP_ERROR :http
    ErrorCode.ABORT      :abort
    ErrorCode.OFFLINE    :offline
    nil))

(defn- translate-response-type
  [type]
  (case type
    :text ResponseType.TEXT
    :blob ResponseType.BLOB
    ResponseType.DEFAULT))

(defn- create-uri
  [uri qs qp]
  (let [uri (Uri. uri)]
    (when qs (.setQuery uri qs))
    (when qp
      (let [dt (.createFromMap QueryData (clj->js  qp))]
        (.setQueryData uri dt)))
    (.toString uri)))

(def default-headers
  {"x-frontend-version" (:full @cfg/version)})

(defn- fetch
  [{:keys [method uri query-string query headers body] :as request}
   {:keys [timeout credentials? response-type]
    :or {timeout 0 credentials? false response-type :text}}]
  (let [uri (create-uri uri query-string query)
        headers (merge default-headers headers)
        headers (if headers (clj->js headers) #js {})
        method (translate-method method)
        xhr (doto (XhrIo.)
              (.setResponseType (translate-response-type response-type))
              (.setWithCredentials credentials?)
              (.setTimeoutInterval timeout))]
    (rx/create
     (fn [sink]
       (letfn [(on-complete [event]
                 (let [type (translate-error-code (.getLastErrorCode xhr))
                       status (.getStatus xhr)]
                   (if (pos? status)
                     (sink (rx/end
                            {:status status
                             :body (.getResponse xhr)
                             :headers (normalize-headers (.getResponseHeaders xhr))}))
                     (sink (rx/end
                            {:status 0
                             :error (if (= type :http) :abort type)
                             ::xhr xhr})))))]
         (events/listen xhr EventType.COMPLETE on-complete)
         (.send xhr uri method body headers)
         #(.abort xhr))))))

(defn success?
  [{:keys [status]}]
  (<= 200 status 299))

(defn server-error?
  [{:keys [status]}]
  (<= 500 status 599))

(defn client-error?
  [{:keys [status]}]
  (<= 400 status 499))

(defn send!
  ([request]
   (send! request nil))
  ([request options]
   (fetch request options)))

(defn fetch-as-data-url
  [url]
  (->> (send! {:method :get :uri url} {:response-type :blob})
       (rx/mapcat (fn [{:keys [body] :as rsp}]
                    (let [reader (js/FileReader.)]
                      (rx/create (fn [sink]
                                   (obj/set! reader "onload" #(sink (reduced (.-result reader))))
                                   (.readAsDataURL reader body))))))))



(defn data-url->blob
  [durl]
  (->> (send! {:method :get :uri durl} {:response-type :blob})
       (rx/map :body)
       (rx/take 1)))
