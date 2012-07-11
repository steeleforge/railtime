(ns railtime.core 
  (:gen-class)
  (:require 
    [clojure.string :as string]
    [cheshire.core :as json]
    [clj-http.client :as http]
    [clojure.tools.cli :as cli]
    [clj-time.core :as time]
    [clj-time.local :as time-l]
    [clj-time.coerce :as time-c]
    [clj-time.format :as time-f])
  (:import (java.util Date)))
  
(def ^:const url-domain "http://metrarail.com/content/metra/en/home/jcr:content/trainTracker.lataexport.html")
(def ^:const url-tracker "http://12.205.200.243/AJAXTrainTracker.svc/GetAcquityTrainData")
(def ^:const delay-threshold-minutes 30)

(defn- encode-date [datetime]
  "turn a java Calendar into an API friendly Date string"
  (str "/Date(" (time/in-msecs 
    (time/interval (time/epoch) datetime)) "-0000)/"))


;; return java Date from "/Date(<timestamp>)/" with an actual date
;; have to handle negative ints due to API oddity
(defn- decode-date [date-str]
  (Date. (Long/valueOf
    (re-find #"-{0,1}\d+" date-str))))

(defn- seq-trains [trains] 
  "turn map of 3 upcoming trains into sequence of trains"
  (seq
    [(:train1 trains) (:train2 trains) (:trains3 trains)]))

(defn- tracker_payload [line origin destination] 
  (json/encode
    {:stationRequest 
      {:Corridor (.toUpperCase line) 
       :Destination (.toUpperCase destination)
       :Origin (.toUpperCase origin)
       :timestamp (encode-date (time-l/local-now))
      }
    }))


(defn request-tracker-trains [url params]
  (json/decode 
    (:d 
      (json/decode 
        (:body 
          (http/post url {
            :body (tracker_payload (:line params) (:origin params) (:destination params))
            :content-type "application/json"})) 
        true)) 
  true))

;; extract lines station {:line->(station)} from html
(defn- parse-lines-stations [html]
  (let [pairs (string/split (string/trim (re-find #"\n\S.+" html)) #"<br \/>")]
    (map
      (fn [pair]
        (let [tuple (string/split pair #",")]
          {(keyword (first tuple)) (vec (rest tuple))}
        )) pairs)))


(defn request-lines-stations [url]
  (apply merge-with 
    concat
    (parse-lines-stations
      (:body (http/get url { :content-type "text/html"})))))


;; utility functions to get key information
(defn available? [train]
  "is train available"
  (not (= (:train_num train) "0000")))
  
(defn estimated [train]
  "get estimated departure time"
  (decode-date (:estimated_dpt_time train)))

(defn scheduled [train] 
  "get estimated departure time"
  (decode-date (:scheduled_dpt_time train)))

(defn delay-interval [train]
  "returns delay in minutes"
  (time/interval   
    (time-c/from-date (scheduled train)) 
    (time-c/from-date (estimated train))))

(defn delayed? [train]
  "true if positive and delay exceeds a threshold"
  (let [m (time/in-minutes (delay-interval train))]
    (and (> m 0) (> m delay-threshold-minutes))))

(defn- str-train-time [datetime]
  "print hour:minute for train"
  (time-l/format-local-time 
    (time-l/to-local-date-time datetime) :hour-minute))
    
(defn- print-train-details [train]
  "print delay information for trains"
  (do
    (if (available? train) (println 
        (str "Train #" (:train_num train)
          "\nScheduled: " (str-train-time (scheduled train)))))
    (if (and (available? train) (delayed? train)) (println 
        (str-train-time "Estimated: " (estimated train))))
    (if (not (available? train))
      (println "[Train Unavailable]"))))

;; CLI
(defn -main [& args]
  (let [[opts args banner] (cli/cli args
           ["-h" "--help" "Show help" :flag true :default false]
           ["-l" "--line" "Line code"]
           ["-o" "--origin" "Origin station code"]
           ["-d" "--destination" "Destination station code"]
           ["-ll" "--list-lines" "List available line codes" :flag true]
           ["-ls" "--list-stations" "List available station codes for a given line code"])]
    (when (:help opts)
      (println banner)
      (System/exit 0))
    (cond
      (:list-lines opts)
        (do
          (println "")
          (doseq [line (keys (request-lines-stations url-domain))]
            (println (name line))))
     (:list-stations opts)
        (do
          (println "")
          (doseq [station ((keyword (:list-stations opts))
            (request-lines-stations url-domain))]
            (println station)))
      (and
         (:line opts)
         (:origin opts)
         (:destination opts))
        (do
          (println "")
          (let [trains (request-tracker-trains url-tracker opts)]
            (doseq [train (keys trains)]
              (if (re-find #"train\d" (name train))
                (print-train-details (train trains))))))
      :else (println banner)
    )))

;;curl -H "Content-Type: application/json" -d "{\"stationRequest\":{\"Corridor\":\"UP-N\",\"Destination\":\"OTC\",\"Origin\":\"EVANSTON\",\"timestamp\":\"/Date(1341469669133-0000)/\"}}" "http://12.205.200.243/AJAXTrainTracker.svc/GetAcquityTrainData"clear