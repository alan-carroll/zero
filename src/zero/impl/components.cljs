(ns zero.impl.components
  (:require
   [zero.impl.base :as base]
   [clojure.string :as str]
   [goog.object :as gobj]))

(defn- flatten-body [body]
  (seq
   (reduce
    (fn [agg nxt]
      (cond
        (seq? nxt) (into agg nxt) 
        (nil? nxt) agg 
        :else (conj agg nxt)))
    []
    body)))

(defn- normalize-vnode
  "Given a vnode like `[tag-or-tags {...props}|...props & body]`
   yields `[tag-or-tags props body]`."
  [vnode]
  (if (map? (nth vnode 1 nil))
    [(nth vnode 0) (nth vnode 1) (flatten-body (nthrest vnode 2))]
    (loop [props {}
           [prop-name prop-val & other :as all] (rest vnode)]
      (if-not (keyword? prop-name)
        [(nth vnode 0) props (flatten-body all)]
        (recur (assoc props prop-name prop-val)
               other)))))

(comment
  (normalize-vnode
   [:div
    :on-click "blah"
    :z/class :none
    "Something else"]))

(defn- extract-tag-props
  "Given a normalized vnode, parses the ids and classes
   out of the tag and into the props, adding an
   additional `:z/sel` prop containing the original
   tag."
  [[tag props body]]
  (if-let [[_ type id classes] (re-matches #"^([^#.]+)([#][^.]+)?([.].+)?$" (name tag))] 
    [(keyword (namespace tag) type)
     (-> props
         (assoc :z/sel tag)
         (assoc :id (some-> id (subs 1)))
         (assoc :z/class (->> [(some-> classes (str/split #"[.]")) (:z/class props)] flatten (remove str/blank?) not-empty)))
     body]
    (throw (ex-info "Invalid tag" {:tag tag}))))

(comment
  (extract-tag-props
   [:div#my-thing.foo.bar {:z/class "something"} (list "body")]))

(defn- preproc-vnode
  "Simplifies the vnode, parsing out the classes and id from
   the tag, and converting compound tags (i.e `[:div :span]`)
   into nested vnodes, and accumulating the whole body into
   one sequence."
  [vnode]
  (let [[tag-or-tags props body] (normalize-vnode vnode)]
    (->
     (cond
       (keyword? tag-or-tags)
       [tag-or-tags props body]
       
       (vector? tag-or-tags)
       (case (count tag-or-tags)
         0 (throw (ex-info "Invalid tag" {:tag tag-or-tags}))
         1 [(first tag-or-tags) props body]
         [(first tag-or-tags) (select-keys props [:z/key])
          (list
           (reduce
            (fn [m middle-tag]
              (extract-tag-props [middle-tag {} (list m)]))
            (extract-tag-props [(last tag-or-tags) (dissoc props :z/key) body])
            (-> tag-or-tags butlast rest)))]))
     extract-tag-props)))

(comment
  (preproc-vnode
   [[:div.foo :span#thing.bar :i]
    :on-click :do-something
    "The" "body"])
  (preproc-vnode
   [::foo :on-click "my-thing"]))

(defonce ^:private PROPS-SYM (js/Symbol "zProps"))
(defonce ^:private MARK-SYM (js/Symbol "zMark"))
(defonce ^:private HTML-NS "http://www.w3.org/1999/xhtml")
(defonce ^:private SVG-NS "http://www.w3.org/2000/svg")
(defonce ^:private PRIVATE-SYM (js/Symbol "zPrivate"))

(defn- default-ns [tag]
  (case tag
    :svg SVG-NS
    nil))

(defn- patch-listeners [^js/Node dom props]
  (let [old-listeners (-> dom (gobj/get PROPS-SYM) :z/on)
        listeners (-> props :z/on)]
    (doseq [[type listener] old-listeners]
      (.removeEventListener dom (name type) listener))
    (doseq [[type listener] listeners]
      (.addEventListener dom (name type) listener))))

(defonce !class->fields-index (atom {}))

(defn- prop-writable? [^js obj prop]
  (if (nil? obj)
    false
    (if-let [prop-def (js/Object.getOwnPropertyDescriptor obj prop)]
      (or (.-writable prop-def) (some? (.-set prop-def)))
      (prop-writable? (js/Object.getPrototypeOf obj) prop))))

(defn- class->fields-index [^js class]
  (let [parent-class (js/Object.getPrototypeOf class)
        proto (.-prototype class)]
    (when proto
      (or
        (get @!class->fields-index class)
        (let [index (->> proto
                         js/Object.getOwnPropertyNames
                         (filter #(prop-writable? proto %))
                         (mapcat
                           (fn [prop-name]
                             [[(keyword (base/snake-case prop-name)) prop-name]
                              [(keyword (base/cammel-case prop-name)) prop-name]]))
                         (into {})
                         (merge (some-> parent-class class->fields-index)))]
          (swap! !class->fields-index assoc class index)
          index)))))

(defn- patch-root-props [^js/ShadowRoot dom ^js/ElementInternals _internals props]
  (when (not= props (gobj/get dom PROPS-SYM))
    (when-let [css (:z/css props)]
      (set! (.-adoptedStyleSheets dom)
            (if (coll? css) (to-array css) #js[css])))
    (patch-listeners dom props)
    ;; TODO: element internals
    (gobj/set dom PROPS-SYM props)))

(defn- ->css-value [x]
  (cond
    (or (keyword? x) (symbol? x))
    (name x)
    
    (vector? x)
    (str/join " " (map ->css-value x))
    
    (seq? x)
    (str/join ", " (map ->css-value x))
    
    :else
    (str x)))

(defn- patch-props [^js/Node dom !binds props]
  (when (not= props (gobj/get dom PROPS-SYM))
    (patch-listeners dom props)
    (let [fields-index (-> dom .-constructor class->fields-index)
          set-prop (fn [dom prop-key prop-value]
                     (if-let [field-name (get fields-index prop-key)]
                       (gobj/set dom field-name prop-value)
                       (.setAttribute dom (name prop-key) (if (true? prop-value) "" (str prop-value)))))]
      (doseq [[k v] props]
        (when (and v (not (namespace k)))
          (cond
            (satisfies? IWatchable v)
            (if-let [existing (get @!binds v)]
              (do
                (swap! !binds update-in [v :binders] conj [dom k])
                (set-prop dom k (:current existing)))
              (let [watch-uuid (random-uuid)
                    current (if (satisfies? IDeref v) (deref v) nil)
                    binders #{[dom k]}]
                (add-watch
                 v watch-uuid
                 (fn [_ _ _ new-val]
                   (swap! !binds assoc-in [v :current] new-val)
                   (doseq [[binder-dom binder-prop] (get-in @!binds [v :binders])]
                     (set-prop binder-dom binder-prop new-val))))
                (swap! !binds assoc v {:uuid watch-uuid :current current :binders binders})
                (set-prop dom k current)))

            :else
            (set-prop dom k v))))
      (doseq [[k v] (gobj/get dom PROPS-SYM)]
        (when (and v (not (namespace k)))
          (when (and (satisfies? IWatchable v) (not= v (get props k)))
            (let [binders (disj (get-in @!binds [v :binders]) [dom k])]
              (cond
                (empty? binders)
                (do
                  (remove-watch v (get @!binds [v :uuid]))
                  (swap! !binds dissoc v))

                :else
                (swap! !binds assoc-in [v :binders] binders))))
          (when (and v (not (get props k)))
            (if-let [prop-name (get fields-index k)]
              (gobj/set dom prop-name (js* "undefined"))
              (.removeAttribute dom k)))))
      (let [style-obj (.-style dom)]
        (doseq [[k v] (:z/style props)]
          (gobj/set style-obj (-> k name base/cammel-case) (->css-value v)))
        (doseq [[k _] (some-> (gobj/get dom PROPS-SYM) :z/style)]
          (when (some-> props :z/style (get k) not)
            (gobj/set style-obj nil))))
      (when-let [class (:z/class props)]
        (set! (.-className dom) (cond->> class (sequential? class) flatten :always (str/join " "))))
      (gobj/set dom PROPS-SYM props))))

(defn- kw->el-name [tag]
  (->
   (if-let [ns (namespace tag)]
     (str ns "-" (name tag))
     (name tag))
   (str/replace #"[^A-Za-z0-9._-]+" "-")
   str/lower-case))

(defn- cleanup-dom [^js/Node dom !binds]
  (doseq [[k v] (gobj/get dom PROPS-SYM)]
    (when (and v (not (namespace k)))
      (when (satisfies? IWatchable v)
        (let [binders (disj (get-in @!binds [v :binders]) [dom k])]
          (cond
            (empty? binders)
            (do
              (remove-watch v (get @!binds [v :uuid]))
              (swap! !binds dissoc v))
  
            :else
            (swap! !binds assoc-in [v :binders] binders))))))
  (doseq [child-dom (-> dom .-childNodes array-seq)]
    (cleanup-dom child-dom !binds)))

(defn- patch-children [^js/Node dom !binds children]
  (let [!child-doms (atom
                     (group-by
                      (fn [child-dom]
                        (if (-> child-dom .-nodeType (= js/Node.TEXT_NODE))
                          :text
                          (let [props (gobj/get child-dom PROPS-SYM)]
                            [(:z/sel props) (:z/key props)])))
                      (-> dom .-childNodes array-seq)))
        !dom-cursor (atom (.-firstChild dom))

        mark-and-inject (fn [^js/Node child-dom]
                          (cond
                            (not= child-dom @!dom-cursor)
                            (if @!dom-cursor
                              (.insertBefore dom child-dom @!dom-cursor)
                              (.appendChild dom child-dom))

                            :else
                            (reset! !dom-cursor (.-nextSibling @!dom-cursor)))
                          (gobj/set child-dom MARK-SYM true))

        take-el-dom (fn [tag props]
                      (let [matcher [(:z/sel props) (:z/key props)]
                            match (->> matcher (get @!child-doms) first)]
                        (if match
                          (do
                            (swap! !child-doms update matcher subvec 1)
                            match)
                          (js/document.createElementNS
                           (or
                            (:xmlns props)
                            (default-ns tag)
                            (.-namespaceURI dom)
                            HTML-NS)
                           (kw->el-name tag)))))
        
        take-text-dom (fn []
                        (if-let [existing (first (get @!child-doms :text))]
                          (do
                            (swap! !child-doms update :text subvec 1)
                            existing)
                          (js/document.createTextNode "")))]
    (doseq [vnode children]
      (cond
        (vector? vnode)
        (let [[tag props body] (preproc-vnode vnode)
              child-dom (take-el-dom tag props)]
          (patch-props child-dom !binds props)
          (patch-children child-dom !binds body)
          (mark-and-inject child-dom))
        
        :else
        (let [child-dom (take-text-dom)]
          (set! (.-nodeValue child-dom) (str vnode))
          (mark-and-inject child-dom))))
    (doseq [dom-child (-> dom .-childNodes array-seq)]
      (when-not (gobj/get dom-child MARK-SYM)
        (cleanup-dom dom-child !binds)
        (.removeChild dom dom-child))
      (gobj/set dom-child MARK-SYM false))))

(defn- patch-w-root [^js/ShadowRoot dom ^js/ElementInternals internals !binds root-vnode]
  (let [[_ props body] (preproc-vnode root-vnode)]
    (patch-root-props dom internals props)
    (patch-children dom !binds body)))

(defn- render [^js/ShadowRoot dom ^js/ElementInternals internals binds vnode]
  (let [!binds (atom (or binds {}))]
    (cond
      (and (vector? vnode) (= (first vnode) :z/root))
      (patch-w-root dom internals !binds vnode)
      
      (seq? vnode)
      (patch-children dom !binds vnode)
      
      :else
      (patch-children dom !binds (list vnode)))
    @!binds))

(defn- normalize-prop-spec [prop-name prop-spec]
  (case prop-spec
    :attr {:attr (-> prop-name name base/snake-case)
           :prop prop-name}
    :field {:field (-> prop-name name base/cammel-case)
            :prop prop-name}
    :prop (merge (normalize-prop-spec prop-name :attr)
                 (normalize-prop-spec prop-name :field))
    (when (map? prop-spec)
      (assoc prop-spec :prop prop-name))))

(defn- remove-binds [binds]
  (doseq [[w {:keys [uuid]}] binds]
    (remove-watch w uuid)))

(defn- patch-el-class [class props view]
  (let [^js proto (.-prototype class)
        ^js static-state (gobj/get class PRIVATE-SYM)
        attr->prop-spec (->> props
                             (keep
                              (fn [[prop-name prop-spec]]
                                (let [normalized-spec (normalize-prop-spec prop-name prop-spec)]
                                  (when-let [attr-name (:attr normalized-spec)]
                                    [attr-name normalized-spec]))))
                             (into {}))
        field->prop-spec (->> props
                              (keep
                               (fn [[prop-name prop-spec]]
                                 (let [normalized-spec (normalize-prop-spec prop-name prop-spec)]
                                   (when-let [field-name (:field normalized-spec)]
                                     [field-name normalized-spec]))))
                              (into {}))
        request-render (fn [^js/Node dom connecting?]
                         (let [^js instance-state (gobj/get dom PRIVATE-SYM)]
                           (when (and (not (.-renderFrameId instance-state))
                                      (.-connected instance-state))
                             (set! (.-renderFrameId instance-state)
                                   (js/requestAnimationFrame
                                    (fn [_]
                                      (set! (.-renderFrameId instance-state) nil)
                                      (set! (.-binds instance-state)
                                            (render
                                             (.-shadow instance-state)
                                             (.-internals instance-state)
                                             (.-binds instance-state)
                                             (view (.-props instance-state))))
                                      (let [shadow (.-shadow instance-state)
                                            event-type (if connecting?  "connect" "update")]
                                        (js/setTimeout
                                         (fn []
                                           (.dispatchEvent shadow (js/Event. event-type #js{:bubbles false}))
                                           (.dispatchEvent shadow (js/Event. "render" #js{:bubbles false})))))))))))]
    (when-not (.-instances static-state)
      (set! (.-instances static-state) #{}))
    (when-not (.-initProps static-state)
      (set! (.-initProps static-state) {}))
    (js/Object.defineProperty
     class "observedAttributes"
     #js{:value (to-array (keys attr->prop-spec))
         :configurable true})
    (js/Object.defineProperties
     proto
     #js{:connectedCallback
         #js{:value
             (fn []
               (let [^js/Node this (js* "this")
                     ^js instance-state (gobj/get this PRIVATE-SYM)]
                 (set! (.-instances static-state) (conj (.-instances static-state) this))
                 (set! (.-connected instance-state) true)
                 (request-render this true)))
             :configurable true}
         :disconnectedCallback
         #js{:value
             (fn []
               (let [^js/Node this (js* "this")
                     ^js instance-state (gobj/get this PRIVATE-SYM)
                     ^js/ShadowRoot shadow (.-shadow instance-state)
                     ^js/ElementInternals internals (.-internals instance-state)]
                 (.dispatchEvent shadow (js/Event. "disconnect" #js{:bubbles false}))
                 (set! (.-instances static-state) (disj (.-instances static-state) this))
                 (set! (.-connected instance-state) false)
                 (let [binds (render shadow internals (.-binds instance-state) nil)]
                   (remove-binds binds)
                   (set! (.-binds instance-state) nil))))
             :configurable true}
         
         :attributeChangedCallback
         #js{:value
             (fn [name _old-val new-val]
               (let [^js/Node this (js* "this")
                     ^js instance-state (gobj/get this PRIVATE-SYM)]
                 (when-let [prop-spec (get attr->prop-spec name)]
                   (cond
                     (nil? new-val)
                     (set! (.-props instance-state) (dissoc (.-props instance-state) (:prop prop-spec)))

                     :else
                     (let [final-val (if-let [mapper (:attr-mapper prop-spec)]
                                       (mapper new-val)
                                       new-val)]
                       (set!
                         (.-props instance-state)
                         (assoc (.-props instance-state) (:prop prop-spec) final-val))
                       (request-render this false))))))
             :configurable true}})
    (doseq [[field-name prop-spec] field->prop-spec]
      (js/Object.defineProperty
        proto
        field-name
        #js{:get
            (fn []
              (-> (js* "this") (gobj/get PRIVATE-SYM) .-props (get (:prop prop-spec))))
            :set
            (fn [x]
              (let [^js instance-state (-> (js* "this") (gobj/get PRIVATE-SYM))]
                (when-not (= x (get (.-props instance-state) (:prop prop-spec)))
                  (cond
                    (js* "~{} === undefined" x)
                    (set! (.-props instance-state) (dissoc (.-props instance-state) (:prop prop-spec)))

                    :else
                    (set! (.-props instance-state) (assoc (.-props instance-state) (:prop prop-spec) x)))
                  (request-render (js* "this") false))))
            :configurable true}))
    (doseq [instance (.-instances static-state)]
      (request-render instance false))))

(defn component [{:keys [name props view]}]
  (let [el-name (kw->el-name name)]
    (if-let [existing (js/customElements.get el-name)]
      (patch-el-class existing props view)
      (let [new-class (js* "
(class ZCustomElementClass extends HTMLElement {
    static [zero.impl.components.PRIVATE_SYM] = {}
    constructor() {
        super()
        this[zero.impl.components.PRIVATE_SYM] = {
            shadow: this.attachShadow({mode: 'open'}),
            internals: this.attachInternals?.(),
            props: ZCustomElementClass[zero.impl.components.PRIVATE_SYM].initProps
        }
    }
})")]
        (patch-el-class new-class props view)
        (js/customElements.define el-name new-class))))
  nil)

(defn component-name [k]
  (kw->el-name k))

(when-not (js/customElements.get (kw->el-name :z/echo))
  (js/customElements.define
    (kw->el-name :z/echo)
    (js* "
(class ZRender extends HTMLElement {
  #shadow; #vdom; #connected; #frameId; #binds

  get vdom() {
    return this.#vdom
  }

  set vdom(vdom) {
    this.#vdom = vdom
    this.#connected && this.#requestRender()
  }
  
  constructor() {
    super()
    this.#shadow = this.attachShadow({mode: 'open'})
  }

  #requestRender() {
    if(this.#frameId || !this.#connected) {
      return
    }
    this.#frameId = requestAnimationFrame(() => {
      this.#frameId = undefined
      this.#binds = zero.impl.components.render(this.#shadow, undefined, this.#binds, this.#vdom)
    })
  }

  connectedCallback() {
    this.#connected = true
    this.#requestRender()
  }

  disconnectedCallback() {
    this.#connected = false
      this.#binds = zero.impl.components.render(this.#shadow, undefined, this.#binds, undefined)
      zero.impl.components.remove_binds(this.#binds)
      this.#binds = undefined
  }

  adoptedCallback() {
  }
})
")))