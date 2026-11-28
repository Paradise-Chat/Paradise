(ns ^:virtualizer/skip paradise.shared.utils.encoding
  (:require [clojure.string :as str]))

(defn uint8->base64
  "Encodes a Uint8Array or ArrayBuffer into a standard Base64 string."
  [bytes]
  (if (or (nil? bytes) (zero? (.-byteLength bytes)))
    ""
    (let [u8     (if (instance? js/Uint8Array bytes)
                   bytes
                   (js/Uint8Array. bytes))
          len    (.-length u8)
          chunk  0x8000
          chunks (js/Array.)]
      (loop [i 0]
        (when (< i len)
          (let [end (min len (+ i chunk))
                sub (.subarray u8 i end)]
            (.push chunks (.apply js/String.fromCharCode nil sub))
            (recur end))))
      (js/btoa (.join chunks "")))))

(defn base64->uint8
  "Decodes a standard Base64 string (ignoring whitespace) into a Uint8Array."
  [b64]
  (let [clean (str/replace (str (or b64 "")) #"\s+" "")]
    (if (str/blank? clean)
      (js/Uint8Array. 0)
      (let [bin (js/atob clean)
            len (.-length bin)
            out (js/Uint8Array. len)]
        (loop [i 0]
          (when (< i len)
            (aset out i (.charCodeAt bin i))
            (recur (inc i))))
        out))))

(defn utf8->base64
  "Encodes a UTF-8 string into a standard Base64 string."
  [s]
  (if (or (nil? s) (empty? (str s)))
    ""
    (let [encoded (.encode (js/TextEncoder.) (str s))]
      (uint8->base64 encoded))))

(defn bytes->base64url
  "Encodes an ArrayBuffer or Uint8Array into unpadded URL-safe Base64."
  [buf]
  (when buf
    (-> (uint8->base64 buf)
        (str/replace "+" "-")
        (str/replace "/" "_")
        (str/replace #"=+$" ""))))

(defn base64url->uint8
  "Decodes a URL-safe Base64 string (with or without padding) into a Uint8Array."
  [b64url]
  (let [s       (str (or b64url ""))
        padding (.repeat "=" (mod (- 4 (mod (.-length s) 4)) 4))
        std-b64 (-> s
                    (str/replace "-" "+")
                    (str/replace "_" "/")
                    (str padding))]
    (base64->uint8 std-b64)))

(defn array-buffer->data-url
  "Converts an ArrayBuffer or Uint8Array into a base64 data: URL."
  [buf mime-type]
  (str "data:" (or mime-type "image/png") ";base64," (uint8->base64 buf)))