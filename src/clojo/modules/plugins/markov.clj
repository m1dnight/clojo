; Author:  Christophe De Troyer
; Email:   christophe.detroyer@gmail.com
; License: GPLv3
; Date:    May 16, 2016

(ns clojo.modules.plugins.markov
  (:use     [clojure.algo.monads]
            [korma.core         ]
            [korma.db           ]
            [clojure.java.io    ])
  (:require [clojo.modules.macros  :as   m]
            [clojo.modules.modules :as mod]
            [clojo.db              :as  db]
            [clojure.tools.logging :as log]
            [clojure.string        :as str]))

;;  _                     _ _                        _        
;; | |__   _____      __ (_) |_  __      _____  _ __| | _____ 
;; | '_ \ / _ \ \ /\ / / | | __| \ \ /\ / / _ \| '__| |/ / __|
;; | | | | (_) \ V  V /  | | |_   \ V  V / (_) | |  |   <\__ \
;; |_| |_|\___/ \_/\_/   |_|\__|   \_/\_/ \___/|_|  |_|\_\___/


;;; We have a few key variables.
;;; stop-string:
;;; This is what we add to the end of the sentence we process. To
;;; tell.. the end.
 
;;; chain-length:
;;; This is what indicates the possible next word. The intuition
;;; behind is that the longer the chain, the better precision you
;;; might have. But the harder it will be to generate a sentence.

;;; Max-words: The maximum number of words a sentence can be.

;;; Given an input sentence "Clojo is a cool bot" we will first
;;; generate the slides for this sentence:
;;; (("Clojo" "is" "a") ("is" "a" "cool") ("a" "cool" "bot") ("cool" "bot" "###stop###"))

;;; Notice that they are one longer than our chain-length. So we then
;;; generate a key-value pair for each of these slides.
;;; ("Clojo" "is" "a") ==> key: "Clojo\"is", value: "a".
;;; We insert these slides into thhe database.

;;; Constructing a sentence is as simple as taking the command (a
;;; sentence) and creatnig slides from that as well. These slides will
;;; then serve as the basis to generate keys from them and do a
;;; reverse lookup.

;;;;;;;;;;;;;;;;;;;;;
;; Database Entity ;;
;;;;;;;;;;;;;;;;;;;;;

(defentity markov
  (database (db/read-db-config)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; HELPERS ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn longest
  [m m']
  (if (> (count m) (count m'))
    m
    m'))


(defn get-lines [fname]
  (with-open [r (reader fname)]
    (doall (line-seq r))))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; DICTIONARY ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;


;; Gets the associated words from the database.
(defn get-words
  [key]
  (map :word (select markov (where {:key key}))))


;; Associated a word with a key.
(defn assoc-word
  [key value]
  (insert markov (values {:key key :word value})))


;; Returns a random word associated with the given key, nil if no
;; words associated.
(defn lookup-rnd-dictionary
  [k]
  (let [words   (get-words k)
        hits    (count words)]
    (when (> hits 0)
      (nth words  (rand-int hits)))))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; MARKOV SHIT ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

;; TODO This should be a control char like \x03 or something. Figure
;; out how to do this in Clojure.
(def stop-string "###stop###")
(def chain-length 4)
(def max-words 100)


;; long-enough checks if an input ismore than long enough.
(defn long-enough
  [msg]
  (< 5 (count msg)))

;; Slides take in a list of items. E.g., [1 2 3]. It will then
;; generate a list of slides. It takes the first `chain-length` elements of
;; the list and then repeats itself with the rest of the list.
;; E.g. (slides [1 2 3]) == ((1 2) (2 3)) for a chain size of 2.
(defn slides
  [xs]
  (cond (empty? xs)
        nil
        (= (count xs) (+ 1 chain-length))
        (list xs)
        :else
        (cons
         (take (+ 1 chain-length) xs)
         (slides (rest xs)))))


;; Generates a key from a list of words.
(defn gen-key
  [words]
  (str/join "\"" words))


;; Takes a key and splits into the words it was made from.
(defn split-key
  [string]
  (str/split string #"\""))


;; Split message takes in a string and returns a list of words. If the
;; words are less than the chain length ignore it.  Otherwise, add the
;; stop word at the end and generate the slides for this sentence.
(defn split-msg
  [input]
  (let [words (conj  (str/split input #" ") stop-string)]
    (when (>  (count words) chain-length)
      (slides words))))



;; key-value takes in a slide and returns a key for in the dictionary.
;; E.g., ("foo" "bar" "baz") -> {:key ("abc" "def"), :value "ghi"}
(defn key-value
  [split]
  {:key   (gen-key (butlast split))
   :value (last split)})


;; Cleans a message for processing.
(defn sanitize-msg
  [msg]
  (-> msg
      (str/lower-case)
      (str/replace "\"" "")
      (str/replace #"[^A-Za-z0-9\s]" "")))


;; process-message takes in a message (i.e., sentence) and processes
;; it and returns a new dictionary.  The message is cleaned, and then
;; all the slides are inserted into the dictionary.
(defn process-message
  [msg]
  (when (long-enough msg)
    (let [clean  (sanitize-msg msg)
          splits (split-msg clean)]
      (doall (map
              (fn [slide]
                (let [{k :key v :value} (key-value slide)]
                  (assoc-word k v)))
              splits)))))


;; Takes a key as input and then constructs a message from the given
;; dictionary.
;; Seed is a vector of size 2 (i.e., a key in the dictionary).
(defn generate-message
  [seed]
  (let [words  (loop [i   max-words        
                      key seed
                      gen []]
                 
                 (let [[k1 k2]  (split-key key)
                       gen'     (conj gen k1)
                       nextword (lookup-rnd-dictionary key)]
                   (cond
                     ;; If we did not find a next word, we did not
                     ;; find a proper ending for a sentence.
                     (or (nil? nextword) (= stop-string nextword))
                     (conj  gen' k2)
                     (> i 0)
                     (recur (dec i) (str/join "\"" [k2 nextword]) gen')
                     :else
                     nil)))]
    (when (and words (long-enough words))
      (str/join " " words))))


;; Compute-message takes in a dictionary and a seed. It simply calls
;; generate-message a few times and then takes the longest string it
;; was able to construct.
(defn compute-message
  [seed]
  (reduce
   longest
   ""
   (map (fn [_] (generate-message seed)) (range 10))))


;; Reply takes in a sentence and generates some responses based on
;; that input.
(defn reply
  [sentence]
  (when (long-enough sentence)
    (let [clean  (sanitize-msg sentence)
          splits (split-msg clean)
          reply  (reduce
                  (fn [best words]
                    (let [{k :key} (key-value words)
                          rply     (compute-message k)]
                      (longest best rply)))
                  splits)]
      (when (long-enough reply)
        reply))))



;;;;;;;;;;;;;;;;;;;;;;;
;; MODULE DEFINITION ;;`
;;;;;;;;;;;;;;;;;;;;;;;

(m/defstorage
  :markov [:key :text] [:word :int])


(m/defmodule
  :markov
  0
  (m/defcommand
    "speak"
    (fn [instance args msg]
      (println args)
      (if-let [response (reply args)]
        (m/reply instance msg response)
        (m/reply instance msg "I have nothing to say!"))))
  (m/defhook
    :PRIVMSG
    (fn [instance msg]
      (println msg)
      (process-message (:message msg))
      (when (> 0.3 (rand))
          (m/reply instance msg (reply msg))))))


;; Place a .txt file in the root of the roject and then you can isnert
;; them into the database with this.


(defn read-from-file
  [filename]
  (let [lines (get-lines filename)
        linec (count lines)]
    (reduce (fn [i line]
              (when (= (mod i 500) 0)
                (println (.format (java.text.SimpleDateFormat. "MM/dd/yyyy HH:MM:ss") (new java.util.Date))
                         "Lines done: "
                         i "/" linec))
              (process-message line)
              (inc i))
            0
            lines)))
