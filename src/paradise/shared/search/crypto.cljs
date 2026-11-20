(ns paradise.shared.search.crypto
  (:require [clojure.string :as str]
            [promesa.core :as p]))

(def ^:const hkdf-salt "paradise.search.salt.v1")
(def ^:const doc-key-info "paradise.search.doc.v1")
(def ^:const tok-key-info "paradise.search.tok.v1")
(def ^:const max-tokens-per-doc 384)
(def ^:private hex-alphabet "0123456789abcdef")

(defn subtle-crypto []
  (or (some-> js/globalThis .-crypto .-subtle)
      (some-> js/crypto .-subtle)))

(defn random-bytes [n]
  (let [arr (js/Uint8Array. n)
        c   (or (.-crypto js/globalThis) js/crypto)]
    (.getRandomValues c arr)
    arr))

(defn utf8->bytes [s]
  (.encode (js/TextEncoder.) (str (or s ""))))

(defn bytes->utf8 [buf]
  (.decode (js/TextDecoder.) buf))

(defn to-uint8 [x]
  (cond
    (instance? js/Uint8Array x) x
    (instance? js/ArrayBuffer x) (js/Uint8Array. x)
    (and x (.-buffer x)) (js/Uint8Array. (.-buffer x) (.-byteOffset x) (.-byteLength x))
    (sequential? x) (js/Uint8Array. (clj->js x))
    :else (js/Uint8Array. 0)))

(defn bytes->hex
  ([u8]
   (bytes->hex u8 (.-length u8)))
  ([u8 max-bytes]
   (let [arr (to-uint8 u8)
         lim (min (.-length arr) max-bytes)
         out (js/Array. (* lim 2))]
     (loop [i 0]
       (when (< i lim)
         (let [b  (aget arr i)
               hi (unsigned-bit-shift-right b 4)
               lo (bit-and b 0x0f)]
           (aset out (* i 2) (.charAt hex-alphabet hi))
           (aset out (inc (* i 2)) (.charAt hex-alphabet lo))
           (recur (inc i)))))
     (.join out ""))))

(defn derive-key-ring [master-secret]
  (let [subtle (subtle-crypto)]
    (when-not subtle
      (throw (js/Error. "WebCrypto SubtleCrypto is unavailable in this environment")))
    (when (str/blank? (str master-secret))
      (throw (js/Error. "Cannot derive search key ring from blank secret")))
    (p/let [base-key (.importKey subtle
                                 "raw"
                                 (utf8->bytes master-secret)
                                 "HKDF"
                                 false
                                 #js ["deriveKey"])
            salt-u8  (utf8->bytes hkdf-salt)
            doc-key  (.deriveKey subtle
                                 #js {:name "HKDF"
                                      :hash "SHA-256"
                                      :salt salt-u8
                                      :info (utf8->bytes doc-key-info)}
                                 base-key
                                 #js {:name "AES-GCM" :length 256}
                                 false
                                 #js ["encrypt" "decrypt"])
            tok-key  (.deriveKey subtle
                                 #js {:name "HKDF"
                                      :hash "SHA-256"
                                      :salt salt-u8
                                      :info (utf8->bytes tok-key-info)}
                                 base-key
                                 #js {:name "HMAC" :hash "SHA-256" :length 256}
                                 false
                                 #js ["sign"])]
      {:doc-key doc-key
       :tok-key tok-key})))

(defn blind-tag [tok-key input-str]
  (p/let [sig-buf (.sign (subtle-crypto)
                         "HMAC"
                         tok-key
                         (utf8->bytes input-str))]
    (bytes->hex (js/Uint8Array. sig-buf) 16)))

(defn room-tag [tok-key room-id]
  (blind-tag tok-key (str "room:" room-id)))

(defn doc-key [tok-key room-id event-id]
  (blind-tag tok-key (str "doc:" room-id "\u0000" event-id)))

(defn checkpoint-key [tok-key room-id]
  (blind-tag tok-key (str "cp:" room-id)))

(defn room-token-key [tok-key room-id token]
  (blind-tag tok-key (str "r:" room-id "\u0000" token)))

(defn global-token-key [tok-key token]
  (blind-tag tok-key (str "g:" token)))

(defn encrypt-record [doc-key aad-str payload-map]
  (let [iv        (random-bytes 12)
        plaintext (utf8->bytes (js/JSON.stringify (clj->js payload-map)))
        params    #js {:name           "AES-GCM"
                       :iv             iv
                       :additionalData (utf8->bytes aad-str)}]
    (p/let [ct-buf (.encrypt (subtle-crypto) params doc-key plaintext)]
      {:iv iv
       :ct (js/Uint8Array. ct-buf)})))

(defn decrypt-record [doc-key aad-str iv ct]
  (let [params #js {:name           "AES-GCM"
                    :iv             (to-uint8 iv)
                    :additionalData (utf8->bytes aad-str)}]
    (p/let [pt-buf (.decrypt (subtle-crypto) params doc-key (to-uint8 ct))
            json-s (bytes->utf8 (js/Uint8Array. pt-buf))]
      (js->clj (js/JSON.parse json-s) :keywordize-keys true))))

(def ^:private word-split-re
  (try
    (js/RegExp. "[^\\p{L}\\p{N}]+" "u")
    (catch :default _
      #"[^a-z0-9\u00c0-\uffff]+")))

(def ^:private quoted-phrase-re #"\"([^\"]+)\"")

(defn normalize-text [s]
  (let [raw (str (or s ""))]
    (-> (if (fn? (.-normalize raw))
          (.normalize raw "NFKC")
          raw)
        str/lower-case)))

(defn split-words [norm-str]
  (if (str/blank? norm-str)
    []
    (->> (.split norm-str word-split-re)
         (into [] (remove str/blank?)))))

(defn word-ngrams [word]
  (let [len (count word)]
    (if (< len 3)
      []
      (loop [i   0
             acc (transient [])]
        (if (> (+ i 3) len)
          (persistent! acc)
          (recur (inc i) (conj! acc (str "n:" (subs word i (+ i 3))))))))))

(defn covering-ngrams [word]
  (let [len (count word)]
    (cond
      (zero? len) []
      (< len 3)   [(str "w:" word)]
      (= len 3)   [(str "n:" word)]
      :else
      (loop [i    0
             seen #{}
             acc  []]
        (if (>= (+ i 3) len)
          (let [tail (str "n:" (subs word (- len 3) len))]
            (if (contains? seen tail)
              acc
              (conj acc tail)))
          (let [tok (str "n:" (subs word i (+ i 3)))]
            (recur (+ i 3)
                   (conj seen tok)
                   (if (contains? seen tok) acc (conj acc tok)))))))))

(defn extract-index-tokens [text]
  (let [words (split-words (normalize-text text))]
    (if (empty? words)
      []
      (let [word-toks  (distinct (map #(str "w:" %) words))
            ngram-toks (distinct (mapcat word-ngrams words))]
        (vec (take max-tokens-per-doc (distinct (concat word-toks ngram-toks))))))))

(defn parse-search-query [raw-query]
  (let [trimmed (str/trim (str (or raw-query "")))]
    (if (str/blank? trimmed)
      {:empty?        true
       :norm-query    ""
       :phrases       []
       :terms         []
       :lookup-tokens []}
      (let [phrases       (->> (re-seq quoted-phrase-re trimmed)
                               (map (comp str/trim normalize-text second))
                               (remove str/blank?)
                               distinct
                               vec)
            unquoted      (str/replace trimmed quoted-phrase-re " ")
            raw-terms     (split-words (normalize-text unquoted))
            phrase-words  (mapcat split-words phrases)
            all-words     (distinct (concat raw-terms phrase-words))
            lookup-tokens (->> all-words
                               (mapcat covering-ngrams)
                               distinct
                               vec)]
        {:empty?        (and (empty? phrases) (empty? raw-terms))
         :norm-query    (normalize-text trimmed)
         :phrases       phrases
         :terms         (vec (distinct raw-terms))
         :lookup-tokens lookup-tokens}))))

(defn matches-parsed-query? [{:keys [empty? phrases terms]} candidate-text]
  (if empty?
    false
    (let [norm-body  (normalize-text candidate-text)
          body-words (when (some #(< (count %) 3) terms)
                       (set (split-words norm-body)))]
      (and (every? #(str/includes? norm-body %) phrases)
           (every? (fn [term]
                     (if (< (count term) 3)
                       (contains? body-words term)
                       (str/includes? norm-body term)))
                   terms)))))