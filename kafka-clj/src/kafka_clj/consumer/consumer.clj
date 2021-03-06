(ns kafka-clj.consumer.consumer
  (:require
    [taoensso.carmine :as car]
    [kafka-clj.consumer.util :as cutil]
    [kafka-clj.redis :as redis]
    [thread-load.core :as load]
    [clojure.core.async :refer [go alts!! >!! >! <! timeout chan] :as async]
    [kafka-clj.fetch :refer [close-fetch-producer create-fetch-producer send-fetch read-fetch]]
    [thread-load.core :as tl]
    [clj-tuple :refer [tuple]]
    [clojure.tools.logging :refer [info error debug warn]])
  (:import
    [io.netty.buffer ByteBuf Unpooled PooledByteBufAllocator]
    [kafka_clj.fetch Message FetchError]
    [java.util.concurrent Future TimeoutException TimeUnit Executors ExecutorService]
    [clj_tcp.client Reconnected Poison]
    [com.codahale.metrics Meter MetricRegistry Timer Histogram]
    [clojure.lang ArityException]
    [io.netty.buffer Unpooled]
    (java.util.concurrent.atomic AtomicInteger)))

;;; This namespace requires a running redis and kafka cluster
;;;;;;;;;;;;;;;;;; USAGE ;;;;;;;;;;;;;;;
;(use 'kafka-clj.consumer.consumer :reload)
;(def consumer (consumer-start {:redis-conf {:host "localhost" :max-active 5 :timeout 1000} :working-queue "working" :complete-queue "complete" :work-queue "work" :conf {}}))
;
;
;
;(publish-work consumer {:producer {:host "localhost" :port 9092} :topic "ping" :partition 0 :offset 0 :len 10})
;
;
;(def res (wait-and-do-work-unit! consumer))
;
;
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defonce byte_array_class (Class/forName "[B"))
(defn- byte-array? [arr] (instance? byte_array_class arr))

(defn read-fetch-message 
  "read-fetch will return the result of fn which is [max-offset-read error-vec]"
  [{:keys [topic partition ^long offset ^long len]} f-delegate v]
  (if v
	  (let [ max-offset (long (+ offset len))
           msg-count (AtomicInteger. 0)
           msg-waste-count (AtomicInteger. 0)

          fetch-res
	         (read-fetch v [0 [] nil -1]
				     (fn [state msg]
	              ;read-fetch will navigate the fetch response calling this function
               ;(info "READ FETCH MESSAGE " msg)
	              (if (coll? state)
			            (let [[^long max-offset-read errors f-state] state
                        ^long msg-offset (:offset msg)]
		               (try
			               (do
					             (cond
								         (instance? kafka_clj.fetch.Message msg)
                         ;only include messsages of the same topic partition and lower than max-offset
                         ;we also check that prev-offset < offset this catches duplicates in a same request
								         (do
                           (if (and (= (:topic msg) topic) (= ^Long (:partition msg) ^Long partition)
                                    (>= msg-offset offset)
                                    (< msg-offset max-offset))
                             (do
                               (.incrementAndGet ^AtomicInteger msg-count)
                               (tuple (Math/max max-offset-read msg-offset) errors (f-delegate f-state msg) (:offset msg)))
                             (do
                               (.incrementAndGet ^AtomicInteger msg-waste-count)
                               (tuple max-offset-read errors f-state))))
								         (instance? kafka_clj.fetch.FetchError msg)
								         (do (error "Fetch error: " msg) (tuple max-offset-read (conj errors msg) f-state))
								         :else (throw (RuntimeException. (str "The message type " msg " not supported")))))
			               (catch Exception e
		                  (do (error e e)
		                      (tuple max-offset-read errors f-state)))))
	                  (do (error "State not supported " state)
	                      [-1 [] nil]))))]

      ;warn when wasting messages
      (if (> (.get ^AtomicInteger msg-waste-count) (/ (.get ^AtomicInteger msg-count) 2))
        (warn "Non optimum consumer config, increase :consume-step or possibly decrease :max-bytes: " (Thread/currentThread) ": fetch msg count: " topic "[" partition "] > count: " (.get ^AtomicInteger msg-count) " waste: " (.get ^AtomicInteger msg-waste-count)))

      (if v
        (.release ^ByteBuf v))
         ;(info "FETCH RESP " fetch-res)
	       (if (coll? fetch-res)
	          fetch-res
		       (do
		         (info "No messages consumed " fetch-res)
		         nil)))))

(defn handle-error-response [v]
  [:fail v])

(defn- error-code [error-vec]
  (->> error-vec (map :error-code) last))

(defn- error-code->status [error-vec]
  (let [code (error-code error-vec)]
    (condp = code
      1 :fail-delete
      :fail)))


(defn wait-or-cancel [^Future f ^Long timeout]
  (try
    (.get f timeout TimeUnit/MILLISECONDS)
    (catch TimeoutException e (do
                                (.cancel f true)
                                nil))))

(defn error-vec->error-status [error-vec]
  (let [delete-fail-status (->>
                             error-vec
                             (map :error-code)
                             (filter #{1 3})                ;OffsetOutOfRange UknownTopicOrPartition
                             first)]
    (if delete-fail-status :fail-delete :fail)))

(defn handle-read-response [work-unit f-delegate v]
  (let [[max-offset-read error-vec] (read-fetch-message work-unit f-delegate v)]
    (tuple (if (empty? error-vec) :ok (error-vec->error-status error-vec)) max-offset-read)))

;@TODO Find a better way of closing the producers
(defn handle-timeout-response [{:keys [producers]}]
  (error "TIMEOUT!! please check the following properties  fetch-timeout, max-wait-time, min-bytes, max-bytes, also check the brokers for any errors")
  (doseq [producer @producers]
    (try
      (close-fetch-producer producer)
      (catch Exception e (error e e))))

  (dosync
    (alter producers (fn [producers]
                       {})))
  [:fail nil])


(defn handle-response
  "Listens to a response after a fetch request has been sent
   Returns [status data]  status can be :ok, :timeout, :error and data is v returned from the channel"
  [{:keys [client] :as state} work-unit f-delegate conf]
  ;(prn "handler-response >>>>> " work-unit)
  (let [fetch-timeout (get conf :fetch-timeout 120000)
        {:keys [read-ch error-ch]} client
        [v c] (alts!! [read-ch error-ch (timeout fetch-timeout)])]
    (condp = c
      read-ch (cond
                (instance? Reconnected v) (do (error "Reconnected")
                                              (handle-response state work-unit f-delegate conf))
                (instance? Poison v) (do
                                       (error "Poinson")
                                       [:fail nil])
                :else (handle-read-response work-unit f-delegate v))
      error-ch (handle-error-response v)
      (handle-timeout-response state))))

    
(defn fetch-and-wait 
  "
   Sends a request for the topic and partition to the producer
   Returns [status data]  status can be :ok, :fail and data is v returned from the channel"
  [state {:keys [topic partition offset len] :as work-unit} producer f-delegate]
  ;in order to avoid reading too many megabyes in a request when its not required
  ;we reduce max bytes by the ratio offset-diff/consume-step
   (let [consume-step (get-in state [:conf :consume-step] 100000)
         max-bytes (let [max-bytes (get-in state [:conf :max-bytes] 104857600)]
                     (if (< len (- consume-step (/ consume-step 8)))
                       (int (* max-bytes (/ len consume-step)))
                       max-bytes))]
     (io!
       ;we always specify a minimum of 1 megabytes
       (send-fetch (assoc-in producer [:conf :max-bytes] (Math/max max-bytes 1048576)) [[topic [{:partition partition :offset offset}]]])
       (handle-response producer work-unit f-delegate (get state :conf)))))


(defn- safe-sleep
  "Util function that does not print an Interrupted exception but handles by setting the current thread to interrupt"
  [ms]
  (try
    (Thread/sleep ms)
    (catch InterruptedException i (doto (Thread/currentThread) (.interrupt)))))

(defn wait-on-work-unit!
  "Blocks on the redis queue till an item becomes availabe, at the same time the item is pushed to the working queue"
  [redis-conn queue working-queue]
  (if-let [res (try                                         ;this command throws a SocketTimeoutException if the queue does not exist
                 (redis/wcar redis-conn                       ;we check for this condition and continue to block
                   (car/brpoplpush queue working-queue 1000))
                 (catch java.net.SocketTimeoutException e (do (safe-sleep 1000) (debug "Timeout on queue " queue " retry ") nil)))]
    res
    (recur redis-conn queue working-queue)))


(defn consumer-start
  "Starts a consumer and returns the consumer state that represents the consumer itself
   A msg-ch can be provided but if not a (chan 100) will be created and assigned to msg-ch in the state.
   keys are:
           :load-pool a load pool from tl/create-pool or if a load-pool exists in the state the same load pool is used
           :msg-ch the core.async channel
           :producers {}
           :status :ok
  "
  [{:keys [redis-conf conf msg-ch load-pool redis-conn] :as state}]
  {:pre [(and 
           (:work-queue state) (:working-queue state) (:complete-queue state)
           (:conf state))]}
  (merge state
    {:load-pool (if load-pool load-pool (tl/create-pool :queue-limit (get conf :consumer-queue-limit 10)))
     :msg-ch (if msg-ch msg-ch (chan 100))
     :producers (ref {})
     :status :ok}))

(defn consumer-stop [{:keys [producers work-queue working-queue] :as state}] (assoc state :status :ok))

(defn create-producer-if-needed!
  "If (get producers producer) returns nil a new producer is created.
  This function returns [producer-connection state]"
  [producers producer conf]
  (if (get producers producer)
    producers
    (assoc producers producer (create-fetch-producer producer conf))))

(defn publish-work-response! 
  "Remove data from the working-queue and publish to the complete-queue"
  [{:keys [redis-conn working-queue complete-queue work-queue work-unit-event-ch] :as state} org-work-units work-unit status resp-data]
  {:pre [work-unit-event-ch redis-conn working-queue complete-queue work-queue work-unit]}
  (let [sorted-wu (into (sorted-map) work-unit)
        work-unit2 (assoc sorted-wu :status status :resp-data resp-data)]

    (if work-unit-event-ch
      (>!! work-unit-event-ch                                 ;send to work-unit-event-channel
           {:event "done"
            :ts (System/currentTimeMillis)
            :wu work-unit2}))
    ;send work complete to complete-queue
    (let [[_ removed] (redis/wcar redis-conn
                        (car/lpush complete-queue work-unit2)
                        (car/lrem  working-queue -1 sorted-wu))]
      state)))

(defn save-call [f state & args]
  (try
    (apply f state args)
    (catch Exception t (do (error t t) (assoc state :status :fail)))))

(defn get-work-unit!
  "Wait for work to become available in the work queue
   Adds a :seen key to the work unit with the current milliseconds"
  [{:keys [redis-conn work-queue working-queue]}]
  {:pre [redis-conn work-queue working-queue]}
  (let [ts (System/currentTimeMillis)
        wu (wait-on-work-unit! redis-conn work-queue working-queue)
        diff (- (System/currentTimeMillis) ts)]
    (if (> diff 1000)
      (info "Slow wait on work unit: took: " diff "ms"))
    wu))

(defn- get-offset-read
  "Returns the max value in the resp data of :offset if no values 0 is returned"
  [resp-data]
  (try
    (apply max (map :offset resp-data))
    (catch ArityException e 0)))

(defn do-work-unit! 
  "state map keys:
    :redis-conn = redis connection params :host :port ... 
    :producers = (ref {}) contains the current brokers to which fetch requests can be sent, these are created dynamically
    :work-queue = the queue name from which work units will be taken, the data must be a map with keys :producer :topic :partition :offset :len
    :working-queue = when a work item is taken from the work-queue its placed on the working-queue
    :complete-queue = when an item has been processed the result is placed on the complete-queue
    :conf = any configuration that will be passed when creating producers
   f-delegate is called as (f-delegate state status resp-data) and should return a state that must have a :status key with values :ok, :fail or :terminate
   
   If the work-unit was successfully processed the work-unit assoc(ed) with :resp-data {:offset-read max-message-offset}
   and added to the complete-queue queue.
   Returns the state map with the :status and :producers updated
  "
  [{:keys [redis-conn producers work-queue working-queue complete-queue conf] :as state} org-work-units work-unit f-delegate]
  ;(info "wait-and-do-work-unit! >>> have work unit " work-unit)
  ;(info "prev-offsets-read: " prev-offsets-read)
  (io!
    (try
      (let [{:keys [producer topic partition offset len]} work-unit
            producer-conn
            ;we need to use a reference on producers here to avoid creating more than one producer per broker.
            ;we we don't N amount of threads will have their own copies of producer causing N*connections and thread pools to be openened.
            (try
              (get (dosync
                     (alter producers
                            (fn [producers]
                              (create-producer-if-needed! producers producer conf)))) producer)
              (catch Exception e (do (error "Error creating producer conn for work-unit " work-unit) (throw e))))]
        (try
          (do
            (if (not producer-conn) (throw (RuntimeException. "No producer created")))
            (let [[status ^long max-offset-read] (fetch-and-wait state work-unit producer-conn f-delegate)
                  state2 (assoc state :status :ok)
                  ]
              (if (and max-offset-read (> max-offset-read -1))
                (publish-work-response! state2 org-work-units work-unit status {:offset-read max-offset-read})
                (do
                  (info ">>>>>>>>>>>>>> nil max-offset-read " max-offset-read  " status " status  " w-unit " work-unit)))

              state2))
          (catch Throwable t (do
                               (.printStackTrace t)
                               (publish-work-response! state org-work-units work-unit :fail nil)
                               (assoc state :status :fail :throwable t :producers  producers)))))
      (catch Throwable t (assoc state :status :fail :throwable t)))))

(defn wait-and-do-work-unit! 
  "
   Deprecated function: please see consume!
   Combine waiting for a workunit and performing it in one function
   The state as returned by do-work-unit! is returned"
  [state f-delegate]
  (let [work-units (get-work-unit! state)]
    ;(prn "wait-and-do-work-unit! >>>>>>> got work")
    (if (map? work-units)
      (do-work-unit! state work-units work-units f-delegate)
      (reduce (fn [state2 work-unit]
                (do-work-unit! state2 work-units work-unit f-delegate)) state work-units))))
    
(defn publish-work 
  "Publish a work-unit to the working queue for a consumer connection"
  [{:keys [redis-conn work-queue]} work-unit]
  {:pre [(and (:producer work-unit) (:topic work-unit) (:partition work-unit) (:offset work-unit) (:len work-unit)
           (let [{:keys [host port]} (:producer work-unit)] (and host port)))
         redis-conn]}
  (io!
    (redis/wcar redis-conn
              (car/lpush work-queue (into (sorted-map) work-unit)))))


(defn- ^Runnable publish-pool-loop [{:keys [load-pool] :as state}]
  (fn []
    (while (not (Thread/interrupted))
      (try
        (tl/publish! load-pool (get-work-unit! state))
        (catch Exception e (error e e))))
    (info ">>>>>>>>>>>>>>>>>>>>>>>>>>> Exit publish pool loop >>>>>>>>>>>>>")
    ))

(defn start-publish-pool-thread 
  "Start a future that will wait for a workunit and publish to the thread-pool"
  [{:keys [load-pool] :as state}]
  {:pre [load-pool]}                                        ;performance update start two push threads
  (doto (Executors/newSingleThreadExecutor) (.submit (publish-pool-loop state))))


(defn close-consumer! [{:keys [load-pool publish-pool]}]
  {:pre [load-pool (instance? ExecutorService publish-pool)]}
  (tl/shutdown-pool load-pool 5000)
  (.shutdownNow ^ExecutorService publish-pool))


(defn- close-for-restart-consumer! [{:keys [load-pool publish-pool]}]
  {:pre [load-pool (instance? ExecutorService publish-pool)]}

  )






(defn consume!
  "Starts the consumer consumption process, by initiating 1+consumer-threads threads, one thread is used to wait for work-units
   from redis, and the other threads are used to process the work-unit, the resp data from each work-unit's processing result is 
   sent to the msg-ch, note that the send to msg-ch is a blocking send, meaning that the whole process will block if msg-ch is full
   The actual consume! function returns inmediately

  "
  [{:keys [conf msg-ch work-unit-event-ch error-handler] :as state}]
  {:pre [conf work-unit-event-ch msg-ch
         (instance? clojure.core.async.impl.channels.ManyToManyChannel msg-ch)
         (instance? clojure.core.async.impl.channels.ManyToManyChannel work-unit-event-ch)]}
  (let [

        {:keys [load-pool] :as ret-state} (merge state (consumer-start state) {:restart 0})
        consumer-threads (get conf :consumer-threads 2)
        ;create a chan per thread, updates are faster and there is less mutex lock contention
        ch-vec (vec (for [i (range consumer-threads)] (chan 10)))
        publish-pool (start-publish-pool-thread ret-state)]


    ;add threads that will consume from the load-pool and run f-delegate, that will in turn put data on the msg-ch
    (dotimes [i consumer-threads]
      (let [ch (ch-vec i)
            ;PERFORMANCE note: pipe per channel is faster than pipe merge channels
            ;we use separate channels per thread to avoid Mutex write waits.
            _ (do (async/pipe ch msg-ch))
            f-delegate (fn [state resp-data]
                          (>!! ch resp-data))]
        (tl/add-consumer load-pool
                         (fn [{:keys [restart] :as state} & _] ;init
                           (info "start consumer thread restart " restart)
                           (if-not restart
                             (assoc ret-state :status :ok :publish-pool publish-pool)
                             (assoc (consumer-start state) :status :ok :restart (inc restart) :publish-pool publish-pool)))
                         (fn [state work-units] ;exec
                           (try
                             (if (map? work-units)
                               (do-work-unit! state work-units work-units f-delegate)
                               (reduce (fn [state2 work-unit]
                                         (do-work-unit! state2 work-units work-unit f-delegate)) state work-units))
                             (catch Exception e (do
                                                  ;we override the status here, from experiments it was found that its
                                                  ;not a good idea to restart threads on failure, rather to report and retry.
                                                  (error "Error doing workunit: ")
                                                  (if error-handler (error-handler :consume state e))
                                                  (assoc state :status :ok)))))
                         (fn [state & args] ;fail
                           (info "Fail consumer thread: " state " " args)
                           (if error-handler (error-handler :consume state (RuntimeException. "failing consumer thread")))
                           (if-let [e (:throwable state)] (error e e))
                           (close-for-restart-consumer! state)
                           (assoc (merge state (consumer-start state)) :status :ok)))))
    ;start background wait on redis, publish work-unit to pool
    (assoc ret-state :publish-pool publish-pool)))
    
                                   
(comment 
  
(use 'kafka-clj.consumer.consumer :reload)
(def consumer {:redis-conf {:host "localhost" :max-active 5 :timeout 1000}
               :work-unit-event-ch (chan (sliding-buffer 10))
               :working-queue "working" :complete-queue "complete" :work-queue "work" :conf {}})
(publish-work consumer {:producer {:host "localhost" :port 9092} :topic "ping" :partition 0 :offset 0 :len 10})
(def res (wait-and-do-work-unit! consumer (fn [state resp-data] resp-data)))

(use 'kafka-clj.consumer.consumer :reload)

(require '[clojure.core.async :refer [go alts!! >!! <!! >! <! timeout chan]])
(def msg-ch (chan 1000))

(def consumer {:redis-conf {:host "localhost" :max-active 5 :timeout 1000} :working-queue "working" :complete-queue "complete" :work-queue "work" :conf {}})
(publish-work consumer {:producer {:host "localhost" :port 9092} :topic "ping" :partition 0 :offset 0 :len 10})
(publish-work consumer {:producer {:host "localhost" :port 9092} :topic "ping" :partition 0 :offset 11 :len 10})

(consume! (assoc consumer :msg-ch msg-ch))

(<!! msg-ch)
(<!! msg-ch)

)


