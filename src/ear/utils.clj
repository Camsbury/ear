(ns ear.utils
  (:require
   [camel-snake-kebab.core :as csk]
   [clojure.data.json :as json]))


(defn from-json [raw]
  (json/read-str raw :key-fn (comp csk/->kebab-case keyword)))
