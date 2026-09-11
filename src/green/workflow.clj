(ns green.workflow
  "The workflow engine: a graph of steps threaded by an opts map.

  - wire-fn: (step run-opts) -> [fn & next-steps] — the static
    happy-path graph for this run. It may depend on stable run-level inputs
    such as :green/event. Multiple successors run in parallel.
  - next-fn (optional): (next-fn step default-next opts) -> seq of
    [next-step opts] pairs — dynamic routing, error branching, fan-out.
  - Steps report outcome via :green/exit (0 ok, >0 error), :green/err,
    :green/trace. Thrown exceptions are caught and converted.
  - Branches converging on the same step join: the join step runs once
    with the fork-point opts plus :green/branches (vector of branch results).
  - A branch failing inside a fork lets the in-flight siblings finish their
    current step, then the fork collapses: the join is skipped and the worst
    exit propagates, with all branch results under :green/branches.
  - advice-add attaches advice to one step; advice-add-all attaches advice
    to every step. Both stack in strict add order (most recently added,
    from either, is outermost), unless a :depth prop overrides placement
    (lower = more outward), as in Emacs.
  - Advice is inherited across `step` embeds: a run stamps its effective
    advice into opts and a nested run merges it over the child's own —
    step names match flat at any depth, ancestor advice is outermost, and
    an ancestor entry replaces a same-id child entry. advice-plan shows
    the composed stack for a step."
  (:require
   [green.advice :as advice])
  (:import
   [java.io PrintWriter StringWriter]))

(defn workflow
  "Construct a workflow. :start is required; :end is an optional slice
  boundary (it runs, then the workflow stops); :wire-fn is required and
  is called as (wire-fn step run-opts); :next-fn is optional."
  [{:keys [start end wire-fn next-fn]}]
  (when-not (keyword? start)
    (throw (ex-info "workflow :start must be a keyword" {:start start})))
  (when-not (fn? wire-fn)
    (throw (ex-info "workflow :wire-fn must be a function" {:wire-fn wire-fn})))
  {::start start ::end end ::wire-fn wire-fn ::next-fn next-fn
   ::advice {} ::advice-all [] ::advice-seq 0})

(defn- next-seq [wf]
  (::advice-seq wf 0))

(defn advice-add
  "Return a workflow with advice `f` added on `step` (combinator `how`,
  explicit `id`). Pure — the original workflow is untouched. Composes with
  any all-steps advice (see `advice-add-all`) in strict add order: whichever
  was added more recently is outermost. `props` may carry :depth
  (-100..100, default 0), which overrides add order: lower depth pushes the
  advice outward, higher pushes it inward, as with Emacs hook depths."
  ([wf step how id f] (advice-add wf step how id f nil))
  ([wf step how id f props]
   (let [s (next-seq wf)]
     (-> wf
         (update ::advice advice/add step how id f s props)
         (assoc ::advice-seq (inc s))))))

(defn advice-remove
  "Return a workflow with the advice registered under `id` on `step` removed."
  [wf step id]
  (update wf ::advice advice/remove-id step id))

(defn advice-add-all
  "Return a workflow with advice `f` added on every step (combinator `how`,
  explicit `id`). Pure — the original workflow is untouched. Composes with
  any per-step advice in strict add order: whichever was added more
  recently is outermost, regardless of whether it came from `advice-add`
  or `advice-add-all`. `props` may carry :depth, as in `advice-add`."
  ([wf how id f] (advice-add-all wf how id f nil))
  ([wf how id f props]
   (let [s (next-seq wf)]
     (-> wf
         (update ::advice-all advice/add-global how id f s props)
         (assoc ::advice-seq (inc s))))))

(defn advice-remove-all
  "Return a workflow with the all-steps advice registered under `id` removed."
  [wf id]
  (update wf ::advice-all advice/remove-global-id id))

;; --- advice inheritance across embeds -------------------------------------
;; A run stamps its effective registries into every step's opts under
;; ::inherited; `step` forwards the stamp into the nested run, whose
;; `inherit` merges it over the child's own advice. Inheritance is
;; transitive because each run stamps its already-merged registries.

(defn- inherit
  "Merge an `inherited` registry payload {:advice :advice-all} from an
  enclosing run over `wf`'s own advice: inherited entries stack outside
  the child's own, and an inherited entry replaces a same-id child entry
  (per step, or in the all-steps list). Step names are flat — whatever an
  ancestor advised under a name applies to this workflow's step of that
  name."
  [wf inherited]
  (if-let [{:keys [advice advice-all]} inherited]
    (let [n (next-seq wf)]
      (-> wf
          (update ::advice advice/merge-registry advice n)
          (update ::advice-all advice/merge-entries advice-all n)))
    wf))

(defn advice-plan
  "Debugging: the advice stack that would wrap `step` at run time,
  outermost first. `wfs` is a single workflow or a chain
  [outermost ... innermost] — the workflows a run traverses to reach
  `step` through `green.workflow/step` embeds (e.g. [parent-wf cluster-wf]
  for an embedded :zk/node). Returns
  [{:id :how :depth :seq :scope :level} ...] where :scope is :step or
  :all and :level indexes the chain element that registered the advice
  (0 = outermost). A child entry replaced by a same-id ancestor entry
  does not appear."
  [wfs step]
  (let [chain (if (map? wfs) [wfs] (vec wfs))
        tag (fn [wf level]
              (-> wf
                  (update ::advice
                          (fn [m]
                            (into {} (map (fn [[k es]]
                                            [k (mapv #(assoc % :scope :step :level level) es)]))
                                  m)))
                  (update ::advice-all
                          (fn [es] (mapv #(assoc % :scope :all :level level) es)))))
        eff (reduce (fn [inherited [level wf]]
                      (let [wf (inherit (tag wf level) inherited)]
                        {:advice (::advice wf) :advice-all (::advice-all wf)}))
                    nil
                    (map-indexed vector chain))
        entries (concat (:advice-all eff) (get (:advice eff) step))]
    (->> (advice/ordered entries)
         reverse
         (mapv #(select-keys % [:id :how :depth :seq :scope :level])))))

;; --- static graph (for join scheduling) ---------------------------------

(defn- static-successors [wire-fn step run-opts]
  ;; Deliberately lenient: this graph exists only for join detection, so a
  ;; name the wire-fn can't resolve (nil or a throw) just contributes no
  ;; static edges — next-fn may own routing for it. Real wiring bugs still
  ;; surface when the step runs: run-step calls the same wire-fn and converts
  ;; the failure to :green/exit. Throwing here would escape run uncaught,
  ;; bypassing the Unix-style outcome contract.
  (try (rest (wire-fn step run-opts)) (catch Exception _ nil)))

(defn- static-graph [wire-fn start run-opts]
  (loop [g {} frontier [start]]
    (if-let [s (first frontier)]
      (if (contains? g s)
        (recur g (subvec frontier 1))
        (let [succ (vec (static-successors wire-fn s run-opts))]
          (recur (assoc g s succ) (into (subvec frontier 1) succ))))
      g)))

(defn- reaches?
  "Can a branch currently at `from` (about to run it) later arrive at `to`,
  following static wire-fn edges?"
  [g from to]
  (loop [seen #{} frontier (vec (get g from))]
    (if (empty? frontier)
      false
      (let [s (peek frontier) frontier (pop frontier)]
        (cond
          (= s to) true
          (seen s) (recur seen frontier)
          :else (recur (conj seen s) (into frontier (get g s))))))))

;; --- running one step ----------------------------------------------------

(defn- stack-trace [^Throwable t]
  (let [sw (StringWriter.)]
    (.printStackTrace t (PrintWriter. sw true))
    (str sw)))

(defn failed?
  "Whether `opts` carries a failing outcome. Steps report failure through
  :green/exit rather than by throwing, so this is the predicate a step composed
  of several sub-steps uses to decide whether to keep going."
  [opts]
  (pos? (:green/exit opts 0)))

(defn- step-failure [opts ^Throwable t]
  (assoc opts
         :green/exit (or (:green/exit (ex-data t)) 1)
         :green/err (or (ex-message t) (str (class t)))
         :green/trace (stack-trace t)))

(defn- scheduler-failure [opts ^Throwable t]
  (assoc opts
         :green/exit 1
         :green/err (or (ex-message t) (str (class t)))
         :green/trace (stack-trace t)))

(defn- with-default-exit [opts]
  (cond-> opts
    (nil? (:green/exit opts)) (assoc :green/exit 0)))

(defn- inherited-payload [wf]
  {:advice (::advice wf)
   :advice-all (::advice-all wf)})

(defn- stamp-inherited [wf opts]
  (assoc opts ::inherited (inherited-payload wf)))

(defn- step-advice [wf step]
  (concat (::advice-all wf) (get-in wf [::advice step])))

(defn- run-step [wf step run-opts opts]
  (try
    (let [decl ((::wire-fn wf) step run-opts)
          f (first decl)]
      (when-not (fn? f)
        (throw (ex-info (str "no function wired for step " step) {:step step})))
      (let [ret ((advice/compose f (step-advice wf step))
                 (assoc (stamp-inherited wf opts) :green/step step))]
        (when-not (map? ret)
          (throw (ex-info (str "step " step " returned a non-map: " (pr-str ret))
                          {:step step})))
        (with-default-exit (dissoc ret ::inherited))))
    (catch Throwable t
      (step-failure opts t))))

(defn- next-pairs
  "Successor [step opts] pairs for `step` after it produced `opts`.
  The end step is a hard boundary; without next-fn an error halts."
  [wf step run-opts opts]
  (if (= step (::end wf))
    []
    (let [dn (seq (rest ((::wire-fn wf) step run-opts)))]
      (if-let [nf (::next-fn wf)]
        (vec (nf step dn opts))
        (if (failed? opts)
          []
          (mapv (fn [s] [s opts]) dn))))))

;; --- the scheduler --------------------------------------------------------

;; Live entries: {:step k :opts m :parent id :forks [frame…]} where a fork
;; frame is {:id fork-id :opts fork-point-opts}. :parent identifies the
;; run-unit that produced the entry, so same-step entries from one fan-out
;; run individually while entries converging from different origins join.
;; Finished branches are {:opts m :forks [frame…]}; the frames let a failure
;; collapse its enclosing fork.

(defn- children [uid opts pairs forks]
  (cond
    (empty? pairs)
    {:terminals [{:opts opts :forks forks}]}

    (= 1 (count pairs))
    (let [[s o] (first pairs)]
      {:new-entries [{:step s :opts o :parent uid :forks forks}]})

    :else
    (let [frame {:id uid :opts opts}]
      {:new-entries (mapv (fn [[s o]]
                            {:step s :opts o :parent uid :forks (conj forks frame)})
                          pairs)})))

(defn- terminal-result [opts forks]
  {:terminals [{:opts opts :forks forks}]})

(defn- branch-worst-exit [branch-opts]
  (apply max (map #(:green/exit % 0) branch-opts)))

(defn- join-forks [entries]
  (let [first-forks (:forks (first entries))]
    (->> first-forks
         (map-indexed vector)
         (take-while (fn [[i frame]]
                       (every? #(= (:id frame) (get-in % [:forks i :id])) entries)))
         (mapv second))))

(defn- failed-join-result [fork-opts forks branch-opts worst]
  (let [bad (first (filter #(= worst (:green/exit % 0)) branch-opts))]
    (terminal-result (assoc fork-opts
                            :green/exit worst
                            :green/err (:green/err bad)
                            :green/trace (:green/trace bad)
                            :green/branches branch-opts)
                     forks)))

(defn- run-single-unit [wf uid step run-opts {:keys [opts forks]}]
  (let [opts' (run-step wf step run-opts opts)]
    (children uid opts' (next-pairs wf step run-opts opts') forks)))

(defn- run-join-unit [wf uid step run-opts entries]
  (let [branch-opts (mapv :opts entries)
        forks (join-forks entries)
        fork-opts (if (seq forks) (:opts (peek forks)) (first branch-opts))
        forks' (if (seq forks) (pop forks) forks)
        worst (branch-worst-exit branch-opts)]
    (if (pos? worst)
      (failed-join-result fork-opts forks' branch-opts worst)
      (let [opts' (run-step wf step run-opts (assoc fork-opts :green/branches branch-opts))]
        (children uid opts' (next-pairs wf step run-opts opts') forks')))))

(defn- unit-base-opts [entry entries]
  (if entry
    (:opts entry)
    (let [forks (join-forks entries)
          base (or (:opts (peek forks)) (:opts (first entries)) {})]
      (assoc base :green/branches (mapv :opts entries)))))

(defn- unit-forks [entry entries]
  (if entry
    (:forks entry)
    (let [forks (join-forks entries)]
      (if (seq forks) (pop forks) forks))))

(defn- failed-unit-result [entry entries ^Throwable t]
  (terminal-result (scheduler-failure (unit-base-opts entry entries) t)
                   (unit-forks entry entries)))

(defn- run-unit [wf run-opts {:keys [kind step entry entries]}]
  (let [uid (gensym "green-unit")]
    (try
      (case kind
        :single (run-single-unit wf uid step run-opts entry)
        :join (run-join-unit wf uid step run-opts entries))
      (catch Throwable t
        (failed-unit-result entry entries t)))))

(defn- failed-fork-branch? [branch]
  (and (failed? (:opts branch)) (seq (:forks branch))))

(defn- in-fork? [fork-id entry]
  (boolean (some #(= fork-id (:id %)) (:forks entry))))

(defn- fork-members [fork-id live finished]
  (into (filterv (partial in-fork? fork-id) finished)
        (filterv (partial in-fork? fork-id) live)))

(defn- outside-fork [fork-id entries]
  (filterv (complement (partial in-fork? fork-id)) entries))

(defn- collapsed-fork-entry [fork-opts bad branch-opts worst]
  (let [worst-opts (first (filter #(= worst (:green/exit % 0)) branch-opts))]
    {:opts (assoc fork-opts
                  :green/exit worst
                  :green/err (:green/err worst-opts)
                  :green/trace (:green/trace worst-opts)
                  :green/branches branch-opts)
     :forks (pop (:forks bad))}))

(defn- collapse-fork [live finished bad]
  (let [{fork-id :id fork-opts :opts} (peek (:forks bad))
        branch-opts (mapv :opts (fork-members fork-id live finished))
        worst (branch-worst-exit branch-opts)]
    [(outside-fork fork-id live)
     (conj (outside-fork fork-id finished)
           (collapsed-fork-entry fork-opts bad branch-opts worst))]))

(defn- collapse-doomed
  "While a finished branch failed inside a fork, collapse that fork: absorb
  its live entries (their current opts are their branch results — siblings
  finished their step, nothing new starts) and its finished branches, skip
  the join, and emit a terminal carrying the worst exit and :green/branches.
  Cascades outward through nested forks."
  [live finished]
  (loop [live live finished finished]
    (if-let [bad (first (filter failed-fork-branch? finished))]
      (let [[live' finished'] (collapse-fork live finished bad)]
        (recur live' finished'))
      [live finished])))

(defn- finalize [finished]
  (let [terminals (mapv #(dissoc (:opts %) ::inherited) finished)]
    (cond
      (empty? terminals) {:green/exit 0}
      (= 1 (count terminals)) (first terminals)
      :else (or (first (filter failed? terminals))
                (last terminals)))))

(defn- blocked-step? [g live step]
  (some #(and (not= (:step %) step) (reaches? g (:step %) step))
        live))

(defn- ready-steps [g live by-step]
  (or (seq (remove #(blocked-step? g live %) (keys by-step)))
      (keys by-step)))

(defn- waiting-steps [by-step ready]
  (remove (set ready) (keys by-step)))

(defn- entries-for-steps [by-step steps]
  (mapcat #(get by-step %) steps))

(defn- same-origin? [entries]
  (or (= 1 (count entries))
      (apply = (map :parent entries))))

(defn- single-units [step entries]
  (map (fn [entry] {:kind :single :step step :entry entry}) entries))

(defn- step-units [step entries]
  (if (same-origin? entries)
    (single-units step entries)
    [{:kind :join :step step :entries entries}]))

(defn- ready-units [by-step ready]
  (mapcat (fn [step] (step-units step (get by-step step))) ready))

(defn- run-units [wf run-opts units]
  (mapv deref (mapv (fn [unit] (future (run-unit wf run-opts unit))) units)))

(defn- scheduler-step [wf run-opts g live finished]
  (let [by-step (group-by :step live)
        ready (ready-steps g live by-step)
        waiting (waiting-steps by-step ready)
        units (ready-units by-step ready)
        results (run-units wf run-opts units)]
    (collapse-doomed
     (-> []
         (into (entries-for-steps by-step waiting))
         (into (mapcat :new-entries results)))
     (into finished (mapcat :terminals results)))))

(declare run)

(defn step
  "Turn a workflow into a step function (opts -> opts), so workflows compose
  into higher-level workflows: wire the result like any other step, advise
  it, fan it out. The sub-workflow's :green/exit propagates naturally, and
  ambient keys like :green/event and :green/dry-run flow in with opts.

  The enclosing run's advice is inherited: the nested run merges it over
  the sub-workflow's own (see the ns docstring). The engine re-stamps the
  inherited registry after :in-fn runs, so :in-fn may build sub-opts from
  scratch without severing inheritance.

  Options:
    :in-fn  (fn [opts] sub-opts)        — shape the opts entering the
                                          sub-workflow
    :out-fn (fn [opts sub-result] opts) — merge the sub-result back into the
                                          parent's opts (default: the
                                          sub-result itself is the step's
                                          result)"
  ([wf] (step wf {}))
  ([wf {:keys [in-fn out-fn]}]
   (fn [opts]
     (let [inherited (::inherited opts)
           sub-opts (cond-> ((or in-fn identity) opts)
                      inherited (assoc ::inherited inherited))
           result (run wf sub-opts)]
       (if out-fn (out-fn opts result) result)))))

(defn run
  "Run the workflow from its start step with `opts` as the initial state.
  The same initial opts are passed to `wire-fn` as `run-opts` for the
  whole run, so the static graph stays stable. Returns the final opts map;
  its :green/exit is the workflow's exit code. When `opts` carries an
  inherited advice registry (stamped by an enclosing run through `step`),
  it is merged over the workflow's own advice before anything runs."
  [wf opts]
  (let [run-opts opts
        wf (inherit wf (::inherited opts))
        g (static-graph (::wire-fn wf) (::start wf) run-opts)]
    (loop [live [{:step (::start wf) :opts opts :parent ::root :forks []}]
           finished []]
      (if (empty? live)
        (finalize finished)
        (let [[live' finished'] (scheduler-step wf run-opts g live finished)]
          (recur live' finished'))))))
