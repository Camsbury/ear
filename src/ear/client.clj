(ns ear.client
  (:require
   [camel-snake-kebab.core :as csk]
   [clj-http.client :as http]
   [clojure.string :as str]
   [clojure.data.json :as json]
   [clojure.java.shell :as sh]
   [ear.utils :as utils]
   [systemic.core :as systemic :refer [defsys]]))

(def SPOTIFY-TOKEN-URI
  "https://accounts.spotify.com/api/token")

(def MAX-LIMIT 50)

(defsys *secrets*
  :start (->> "secrets.edn"
              slurp
              read-string))

(defsys *access-token*
  :deps [*secrets*]
  :start
  (->
   (http/post SPOTIFY-TOKEN-URI
              {:form-params {:grant_type       "refresh_token"
                             :refresh_token    (:spotify-refresh-token *secrets*)}
               :basic-auth [(:spotify-client-id *secrets*)
                            (:spotify-client-secret *secrets*)]})
   :body
   utils/from-json
   :access-token))

(systemic/start! *access-token*)

(defn get-spotify
  "GET request at the current URL"
  [endpoint & {:as query-params}]
  (-> (str "https://api.spotify.com/v1" endpoint)
      (http/get {:oauth-token *access-token*
                 :query-params
                 (into
                  {}
                  (map (fn [[k v]] [(csk/->snake_case (name k)) v]))
                  query-params)})
      :body
      utils/from-json))

(defsys *track-count*
  :deps [*access-token*]
  :start (:total (get-spotify "/me/tracks")))

(defsys *my-liked-songs*
  :deps [*access-token*]
  :start (let [track-count (:total (get-spotify "/me/tracks"))]
           (into [] cat
                 (for [offset (range 0 track-count MAX-LIMIT)]
                   (seq (:items
                         (get-spotify
                          "/me/tracks"
                          :limit MAX-LIMIT :offset offset)))))))

(systemic/start! *my-liked-songs*)

(defn open-spotify-uri
  [uri]
  (sh/sh "dbus-send"
         "--print-reply"
         "--dest=org.mpris.MediaPlayer2.spotify"
         "/org/mpris/MediaPlayer2"
         "org.mpris.MediaPlayer2.Player.OpenUri"
         (str "string:" uri)))

(defn open-random []
  (-> *my-liked-songs*
      (get (rand-int *track-count*))
      :track
      :uri
      open-spotify-uri))

(comment
  (open-random))

;;; get the refresh token
(comment
  (require '[ring.util.codec :refer [form-decode]]) ;; add as dep
  (require '[clojure.walk :refer [keywordize-keys]])
  (def REDIRECT-URI "http://localhost:3000")
  (def SCOPES
    ["ugc-image-upload"
     "user-read-playback-state"
     "user-modify-playback-state"
     "user-read-currently-playing"
     "streaming"
     "app-remote-control"
     "user-read-email"
     "user-read-private"
     "playlist-read-collaborative"
     "playlist-modify-public"
     "playlist-read-private"
     "playlist-modify-private"
     "user-library-modify"
     "user-library-read"
     "user-top-read"
     "user-read-playback-position"
     "user-read-recently-played"
     "user-follow-read"
     "user-follow-modify"])
  (def state-val (apply str (take 10 (repeatedly #(char (+ (rand-int 95) 33))))))
  ;; TODO: go to this site
  (first
   (:trace-redirects
    (http/get "https://accounts.spotify.com/authorize"
              {:query-params {"client_id"     (:spotify-client-id *secrets*)
                              "response_type" "code"
                              "redirect_uri"  REDIRECT-URI
                              "state"         state-val
                              "scope"         (str/join " " SCOPES)}})))
  (def auth-code
    (let [q-params "";; TODO: enter query param string here
          {:keys [code state]} (keywordize-keys (form-decode q-params))]
      (when (= state state-val)
        code)))
  (try
    (-> (http/post SPOTIFY-TOKEN-URI
                   {:form-params {:code          auth-code
                                  :grant_type    "authorization_code"
                                  :client_id     (:spotify-client-id *secrets*)
                                  :client_secret (:spotify-client-secret *secrets*)
                                  :redirect_uri  "http://localhost:3000"}})
        :body
        (json/read-str :key-fn (comp csk/->kebab-case keyword))
        :refresh-token)
    (catch Exception _ nil)))
