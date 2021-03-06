(ns zest.core
  (:require-macros [cljs.core.async.macros :refer [go go-loop]])
  (:require [reagent.core :as reagent]
            [cljs.core.async :as async]
            [clojure.set :refer [union]]
            [cljsjs.react]
            [goog.net.XhrIo]
            [zest.settings]
            [zest.docs.registry]
            [zest.docs.stackoverflow]
            [zest.searcher]
            [zest.docs.devdocs]))

(defn get-binary-path [filename]
  (let [dir (.-__dirname js/window)
        path (.require js/window "path")]
    (.join path (.dirname path dir) filename)))

(def so-db (atom nil))

(defn set-so-db []
  (reset! so-db
    (let [levelup (.require js/window "levelup")
            path (.require js/window "path")]
        (levelup (.join path (zest.docs.registry/get-so-root)
                        "leveldb")))))

(set-so-db)

(def so-index
  (let
    [mkdirp (.require js/window "mkdirp")
     path (.require js/window "path")]
    (.sync mkdirp (zest.docs.registry/get-so-root))
    (.join path (zest.docs.registry/get-so-root) "lucene")))

(defn normalize-str [s]
  (->
    s
    (.toLowerCase)
    (.replace "..." "")
    (.replace (js/RegExp. "\\ event$") "")
    (.replace (js/RegExp. "\\.+" "g") ".")
    (.replace (js/RegExp. "\\(\\w+?\\)$") "")
    (.replace (js/RegExp. "\\s" "g") "")
    (.replace "()" "")
    ; separators:
    (.replace (js/RegExp. "\\:?\\ |#|::|->|\\$(?=\\w)" "g") ".")))

(defn insert-doc [prep doc]
  (let [res (async/chan)]
    (.run
      prep
      (normalize-str (.-name (.-contents doc)))
      (.-name (.-contents doc))
      (.-docset doc)
      (.-path (.-contents doc))
      (fn [] (go (async/>! res true))))
    res))

(def symbol-db (atom nil))

(def app-dir
  (let [path (.require js/window "path")]
    (.join path
      (.dirname path (.-__dirname js/window)))))

(def extension-path
  (let [path (.require js/window "path")]
    (.join path
       app-dir
       "sqlite_score"
       "zest_score.sqlext")))

(defn open-symbol-db [cb] (let [path (.require js/window "path")
                                sqlite3 (.require js/window "sqlite3")
                                Database (.-Database sqlite3)
                                db-path (.join path (zest.docs.registry/get-devdocs-root) "symbols")
                                d (Database. db-path cb)]
                            (.loadExtension d extension-path
                                            (fn [e] (.log js/console e)))
                            (reset! symbol-db d)))

(defn rebuild-symbol-db []
  (let [sqlite3 (.require js/window "sqlite3")
        Database (.-Database sqlite3)
        path (.require js/window "path")
        db-chan (let [db-path (.join path (zest.docs.registry/get-devdocs-root) "new_symbols")]
             (go (async/<! (zest.settings/async-rimraf db-path))
                 (Database. db-path)))
        db (atom nil)
        docs (atom @zest.docs.devdocs/entries)
        ret (async/chan)
        i (atom 0)]
    (go
      (reset! db (async/<! db-chan))
      (.loadExtension @db extension-path
                      (fn [e] (.log js/console e)))
      (.exec @db "CREATE TABLE idx (ns, s, docset, path);  BEGIN;"
             (fn []
               (let [prep (.prepare @db "INSERT INTO idx VALUES (?, ?, ?, ?)")]
                 (go-loop
                   []
                   (if (empty? @docs)
                     (do
                       (.finalize prep)
                       (.run @db "COMMIT"
                             (fn [] (.close @db #(go (async/>! ret true))))))
                     (do
                       (if (= (mod @i 1000) 999)
                         (.log js/console (inc @i)))
                       (reset! i (inc @i))
  
                       (async/<! (insert-doc prep (first @docs)))
                       (reset! docs (rest @docs))
                       (recur))))))))
      ret))


(def query (reagent/atom ""))
(def query-immediate (reagent/atom nil))
(def query-timeout (reagent/atom nil))
(def nonfts-results (reagent/atom []))
(def nonfts-keys (atom #{}))
(def nonfts-cursor (atom nil))
(def results (reagent/atom []))
(def search-results (reagent/atom []))
(def index (reagent/atom 0))

(def devdocs-key #(str (.-docset %) "/" (.-path (.-contents %))))

(defn query-symbol-db [query]
  (let [res (async/chan)
        prep (.prepare
               @symbol-db
               "SELECT s, docset, path, zestScore(?, ns) AS score FROM idx WHERE score > 0 ORDER BY score DESC LIMIT 100")]
    (.all prep query
          (fn [e data]
            (go (async/>! res (map
                                #(js-obj "docset" (.-docset %)
                                         "contents" (js-obj "path" (.-path %)
                                                            "name" (.-s %)))
                                data)))))
    res))

(defn do-match-chunks [query]
  (.interrupt @symbol-db)
  (go (reset!
        results
        (concat
          (async/<! (query-symbol-db query))
          [(js-obj "contents" (js-obj "path" "__FTS__"
                                      "name" "More DevDocs results..."))]))))

(defn match-chunks [query]
  (reset! query-timeout
          (.setTimeout js/window (do-match-chunks query) 1)))

(defn set-query [q]
  (reset! query q)
  (if (= (count q) 0)
    (reset! results [])
    (do
      (let [prep-query (.replace @query #"\s+$" "")]
        (go (reset! search-results
                    (async/<! (zest.searcher/search so-index prep-query)))
            (if (= (count @search-results) 0)
              (reset!
                search-results
                (async/<!
                  (zest.searcher/search so-index (str prep-query "*")))))))
      (match-chunks q)
      (reset! index 0))))

(defn render-so-post [data]
  (let [render-template
        (fn [data]
          (let [handlebars (.require js/window "handlebars")
                fs (.require js/window "fs")
                path (.require js/window "path")
                tpl (.compile
                      handlebars
                      (.readFileSync fs
                                     (.join path
                                       app-dir  "app/templates/post.handlebars")
                                     "utf8"))]
            (.registerHelper
              handlebars
              "ifPlural"
              (fn [var options]
                (if (not (= 1 (int var)))
                  (this-as js-this (.fn options js-this)))))
            (.registerPartial
              handlebars
              "renderComments"
              (.readFileSync fs (.join path
                                       app-dir "app/templates/comments.handlebars")
                             "utf8"))
            (.log js/console data)
            (tpl (js-obj "post" data))))]
    (go (render-template (async/<!
                           (zest.docs.stackoverflow/process-so-post data true))))))

(defn main-page
  []
  (let
    [escape-html (.require js/window "escape-html")
     docset-types (reagent/atom (hash-map))
     docsets-list zest.docs.registry/installed-devdocs-atom
     docset-type-items (reagent/atom (hash-map))
     splitjs (.require js/window "split.js")
     html (reagent/atom "")
     input-focus (reagent/atom false)
     focus-id (reagent/atom nil)

     LuceneIndex
     (.-LuceneIndex (.require js/window "nodelucene"))
     path (.require js/window "path")

     get-search-index #(LuceneIndex.
                        (.join path (zest.docs.registry/get-devdocs-root)
                               "lucene"))

     fts-results
     (fn [files cb]
       (let
         [res (reagent/atom "")
          i (reagent/atom 0)
          search-index (get-search-index)

          next
          (fn next []
            (let [docset (nth (.split (nth files @i) "/") 0)
                  path (.join (.slice (.split (nth files @i) "/") 1) "/")]
              (zest.docs.devdocs/get-from-cache docset)
              (let [db-cache (aget zest.docs.devdocs/docset-db-cache docset)]
                (reset!
                  res
                  (str
                    @res
                    "<h5><a href='javascript:openDoc(\""
                    docset "\", \"" path "\""
                    ")'>"
                    (nth files @i)
                    "</a></h5>"
                    (.highlight
                      search-index
                      (escape-html
                        (zest.docs.stackoverflow/unfluff
                          (aget db-cache path)))
                      @query)))
                (if (< @i (- (count files) 1))
                  (do (reset! i (+ @i 1)) (next))
                  (do (cb @res))))))]
         (next)))

     set-600ms-focus
     (fn []
       (reset! input-focus true)
       (if focus-id (.clearTimeout js/window @focus-id))
       (reset! focus-id (.setTimeout js/window #(reset! input-focus false) 600)))
     hash (reagent/atom "#")
     right-class (reagent/create-class
                   {:component-did-mount
                    (fn []
                      (splitjs
                        (array "#left", "#right")
                        (js-obj
                          "sizes" (array 25 75))))


                    :reagent-render
                    (fn []
                      [:div
                       {:id "right"}])})

     async-num (atom 0)
     async-set-html
     (fn [new-html done-cb]
       (let
         [target (.getElementById js/document "right")
          temp (atom (.createElement js/document "div"))
          frag (.createDocumentFragment js/document)
          cur-num (+ @async-num 1)

          proceed
          (fn proceed []
            (if (= cur-num @async-num)
              (if (.-firstChild @temp)
                (do (.appendChild frag (.-firstChild @temp))
                    (.setImmediate js/window proceed))
                (do (set! (.-innerHTML target) "")
                    (.appendChild target frag)
                    (done-cb)))))]

         (reset! async-num (+ @async-num 1))
         (reset! html new-html)
         (set! (.-innerHTML @temp) new-html)
         (proceed)))

     set-hash
     (fn [new-hash]
       (if (nil? new-hash)
         (set! (.-scrollTop (.getElementById js/document "right")) 0)
         (.setImmediate
           js/window
           (fn []
             ; in case new-hash = current hash:
             (set! (.-hash js/location) "")
             (.setImmediate
               js/window
               #(set! (.-hash js/location) new-hash))))))

     activate-item
     (fn [docset entry]
       (.log js/console docset entry)
       (if (= 0 (.indexOf docset "stackoverflow"))
         (let []
           (set! (.-scrollTop (.getElementById js/document "right")) 0)
           (.get
             @so-db
             (str "p_" entry)
             (fn [e json]
               (let [data (.parse js/JSON json)]
                 (aset (.getElementById js/document "right") "className"
                       "")
                 (go (async-set-html (async/<! (render-so-post data)) #()))))))
         (let [_docset  (zest.docs.devdocs/get-from-cache docset)   ;; stupid hack to populate db cache (FIXME)
               response (aget (aget zest.docs.devdocs/docset-db-cache docset)
                              (nth (.split (.-path entry) "#") 0))]
           (let [new-hash (nth (.split (.-path entry) "#") 1)]
             (if (= @html response)
               (do
                 (set-hash new-hash)
                 (set-600ms-focus))

               (do
                 (reset! hash new-hash)
                 (aset (.getElementById js/document "right") "className"
                       (str "_" (nth (.split docset "~") 0)))
                 (async-set-html response #(set-hash new-hash))
                 (set-600ms-focus)))))))

     refresh
     (fn []
       (let [entry (nth @results @index)
             entry-path (.-path (.-contents entry))]
         (if (= "__FTS__" entry-path)
           (fts-results (.search (get-search-index) @query)
                        #(async-set-html
                          %
                          (fn [] (set! (.-scrollTop
                                         (.getElementById js/document "right")) 0))))
           (activate-item (.-docset entry) (.-contents entry)))))

     render-item
     (fn [i item]
       ^{:key (str @query (str i))}
       [:a
        {:class    (str (if (= @index i) "collection-item active"
                                         "collection-item")
                        (if (not (= (.-path (.-contents item)) "__FTS__"))
                          (str " _list-item " "_icon-"
                               (nth (.split (.-docset item) "~") 0))
                          ""))
         :href     "#"
         :on-click (fn []
                     (reset! index i)
                     (refresh)
                     (.focus (.getElementById js/document "searchInput")))}
        (.-name (.-contents item))])

     section
     (fn [docset type]
       [:div {:class "collection"}
        (for
          [entry (get-in @docset-type-items [docset type])]
          ^{:key (str docset "_" (.-name type) "_" (.-path entry) "_" (.-name entry))}

          [:a
           {:class    "collection-item"
            :href     "#"
            :on-click #(activate-item docset entry)}
           (.-name entry)])])

     fts-suggestions
     (fn []
       (if (> (count @search-results) 0)
         (do [:div {:class "collection with-header"}
              [:div {:class "collection-header z-depth-1"}
               [:strong "Stack Overflow"]]
              (map
                (fn [res]
                ^{:key (js/btoa (js/unescape (js/encodeURIComponent res)))}
                [:a
                 {:href     "#"
                  :class    "collection-item"
                  :on-click #(activate-item "stackoverflow"
                                            (nth (.split res ";") 0))}
                 (nth (.split res ";") 1)])
                @search-results)])
         [:div]))]

    (set! (.-openDoc js/window)
          (fn [docset item]
            (activate-item docset (js-obj "path" item))))

    (reagent/create-class
      {:reagent-render
       (fn []
         [:div {:style {:height "100%"}}
          [:div {:id "left"}
           [:input
            {:id   "searchInput"
             :type "text"
             :on-blur
                   (fn [e] (if @input-focus
                             (.focus (.getElementById js/document "searchInput"))))
             :on-key-down
                   (fn [e]
                     (case (.-key e)
                       "ArrowDown" (reset! index (mod (+ @index 1) (count @results)))
                       "ArrowUp" (reset! index (mod (- @index 1) (count @results)))
                       "Escape" (do (set! (.-value (.-target e)) "") (set-query ""))
                       "Enter" ())
                     (refresh)
                     (set-600ms-focus)
                     false)
             :on-change
                   (fn [e]
                     (let [q (-> e .-target .-value)]
                       (set-query q)))}]
           [:div {:class "collapsible tree"}
            (if (= 0 (count @results))
              (doall (for [docset
                           (map (fn [x] {:name x :label x}) @docsets-list)]
                       ^{:key (:name docset)}
                       [:div
                        [:div {:class
                               (str "collapsible-header _list-item _icon-"
                                    (nth (.split (:name docset) "~") 0))
                               :on-click
                               (fn []
                                 (if (nil? (get @docset-types (:name docset)))
                                   (reset!
                                     docset-types
                                     (assoc @docset-types
                                       (:name docset)
                                       (.-types (zest.docs.devdocs/get-from-cache (:name docset)))))
                                   (reset! docset-types (dissoc @docset-types (:name docset)))))}
                         (:label docset)]
                        (doall (for [type (get @docset-types (:name docset))]
                                 (let []
                                   ^{:key (str (:name docset) "/" (.-name type))}
                                   [:div {:class "collapsible level2"}
                                    [:div {:class "collapsible-header"
                                           :on-click
                                                  (fn []
                                                    (if (nil? (get-in @docset-type-items [(:name docset) type]))
                                                      (reset! docset-type-items
                                                              (assoc-in @docset-type-items [(:name docset) type]
                                                                        (filter
                                                                          #(= (.-type %) (.-name type))
                                                                          (.-entries (zest.docs.devdocs/get-from-cache (:name docset))))))
                                                      (reset! docset-type-items
                                                              (dissoc (get @docset-type-items (:name docset)) type))))}

                                     (.-name type)]
                                    [section (:name docset) type]])))]))
              [:div {:class "collection"} (doall (for [[i item] (map-indexed vector @results)]
                                                   (render-item i item)
                                                   ))])
            (if (> (count @query) 0) (fts-suggestions))]]
          [right-class]
          [zest.settings/register-settings]])})))

(defn mount-root
  []
  (set! (.-onkeydown js/document)
        (fn [e]
          (if (= (.-keyCode e) 27)
            (do
              (.closeModal (.$ js/window ".modal"))))))
  (reagent/render
    [main-page]
    (.getElementById js/document "app")))

(defn make-devdocs-loop []
  (.log js/console "make-devdocs-loop")
  (go-loop []
           (let [data (async/<! zest.docs.registry/installed-devdocs-chan)]
             (reset! zest.docs.devdocs/entries
                     (zest.docs.devdocs/get-all-entries data))
             (if (> (count @query) 0)
               (set-query @query))
             (recur))))

(def devdocs-loop (make-devdocs-loop))

(defn before-figwheel-reload []
  (.log js/console "before")
  (.close @so-db)
  (.close @symbol-db)
  (zest.searcher/stop-all-searchers)
  (.removeEventListener
    (.-body js/document)
    "figwheel.before-js-reload"
    before-figwheel-reload)
  (async/close! devdocs-loop))

(defn add-figwheel-handler []
  (.addEventListener
    (.-body js/document)
    "figwheel.before-js-reload"
    before-figwheel-reload))

(defn init!
  []
  (add-figwheel-handler)
  (zest.searcher/new-searcher so-index)
  (open-symbol-db nil)
  (mount-root))

(defn on-figwheel-reload []
  (init!))
