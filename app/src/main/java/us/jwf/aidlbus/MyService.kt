package us.jwf.aidlbus

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import kotlinx.atomicfu.AtomicRef
import kotlinx.atomicfu.atomic
import kotlinx.atomicfu.update
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.ConflatedBroadcastChannel
import kotlinx.coroutines.channels.sendBlocking
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.*

class MyService : Service() {
    private val scope = CoroutineScope(Job() + Dispatchers.IO)
    private val eventBus = EventBus(scope)
    private val oddsFlow = flow<EventBusProtos.Event> {
        var value = 1
        val builder =
            EventBusProtos.Event.newBuilder()
                .setChannel(CHANNEL_NAME_ODDS)
                .setType("New Value")
        while (true) {
            emit(builder.setData(value).build())
            value += 2
            delay(10)
        }
    }
    private val evensFlow = flow<EventBusProtos.Event> {
        var value = 0
        val builder =
            EventBusProtos.Event.newBuilder()
                .setChannel(CHANNEL_NAME_EVENS)
                .setType("New Value")
        while (true) {
            emit(builder.setData(value).build())
            value += 2
            delay(10)
        }
    }
    private val fibonacciFlow = flow<EventBusProtos.Event> {
        var a = 0
        var b = 1
        val builder =
            EventBusProtos.Event.newBuilder()
                .setChannel(CHANNEL_NAME_FIBO)
                .setType("New Value")
        while (true) {
            emit(builder.setData(b).build())
            val newB = a + b
            a = b
            b = newB
            delay(10)
        }
    }
    private val oddsJob: AtomicRef<Job> = atomic(Job())
    private val evensJob: AtomicRef<Job> = atomic(Job())
    private val fibonacciJob: AtomicRef<Job> = atomic(Job())

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val superValue = super.onStartCommand(intent, flags, startId)
        val channelName = intent?.getStringExtra(EXTRA_CHANNEL_NAME) ?: return superValue
        val startStop = intent.getBooleanExtra(EXTRA_SHOULD_START, false)

        val (flow, job) = when (channelName) {
            CHANNEL_NAME_ODDS -> oddsFlow to oddsJob
            CHANNEL_NAME_EVENS -> evensFlow to evensJob
            CHANNEL_NAME_FIBO -> fibonacciFlow to fibonacciJob
            else -> return superValue
        }

        if (!startStop) {
            job.value.takeIf { it.isActive }?.cancel()
        } else {
            job.value.takeIf { it.isActive }?.cancel()
            job.value = scope.launch { flow.collect { eventBus.sendEvent(it) } }
        }

        return superValue
    }

    override fun onBind(intent: Intent): IBinder = eventBus

    override fun onDestroy() {
        super.onDestroy()
        eventBus.close()
        scope.cancel()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    class EventBus(private val scope: CoroutineScope) : IEventBus.Stub() {
        private val listenerMutex = Mutex()
        private val listeners = mutableMapOf<String, IEventListener>()
        private val listenerDeathRecipients = mutableMapOf<String, IBinder.DeathRecipient>()

        private val channelsMutex = Mutex()
        private val channels =
            mutableMapOf<String, ConflatedBroadcastChannel<EventBusProtos.Event>>()

        private val subs = atomic(ChannelSubscriptions())

        fun sendEvent(event: EventBusProtos.Event) {
            scope.launch {
                getChannel(event.channel).also { channel ->
                    channel.send(event)
                    if (event.type == "Deleted") channel.close()
                }
            }
        }

        fun close(): Unit = runBlocking {
            channelsMutex.withLock {
                channels.values.forEach { it.close() }
                channels.clear()
            }

            listenerMutex.withLock {
                listeners.clear()
                listenerDeathRecipients.clear()
            }
        }

        private suspend fun getChannel(channelName: String): ConflatedBroadcastChannel<EventBusProtos.Event> {
            return channelsMutex.withLock {
                channels.compute(channelName) { _, channel ->
                    channel ?: ConflatedBroadcastChannel()
                }!!
            }
        }

        override fun registerListener(listener: IEventListener): String = runBlocking {
            addListener(listener)
        }

        override fun subscribeListenerTo(listenerId: String, channel: String) {
            val job = scope.launch {
                getChannel(channel)
                    .asFlow()
                    .collect {
                        listenerMutex.withLock { listeners[listenerId] }?.onEvent(it.toByteArray())
                    }
            }

            var jobToCancel: Job? = null
            subs.update { value ->
                val (newValue, oldJob) = value.addSubscription(channel, listenerId, job)
                jobToCancel = oldJob
                newValue
            }

            jobToCancel?.cancel()
        }

        override fun unsubscribeListenerFrom(listenerId: String, channel: String) {
            var jobToCancel: Job? = null

            subs.update { value ->
                val (newValue, oldJob) = value.removeSubscription(channel, listenerId)
                jobToCancel = oldJob
                newValue
            }

            jobToCancel?.cancel()
        }

        override fun unregisterListener(listenerId: String) {
            scope.launch { removeListener(listenerId) }
        }

        private suspend fun addListener(listener: IEventListener): String = listenerMutex.withLock {
            val id = UUID.randomUUID().toString()
            val deathRecipient = IBinder.DeathRecipient {
                scope.launch { removeListener(id) }
                Unit
            }
            listener.asBinder().linkToDeath(deathRecipient, IBinder.FLAG_ONEWAY)
            listeners[id] = listener
            listenerDeathRecipients[id] = deathRecipient
            id
        }

        private suspend fun removeListener(listenerId: String) {
            listenerMutex.withLock {
                listeners.remove(listenerId)
                listenerDeathRecipients.remove(listenerId)
            }?.let {
                var jobsToCancel: List<Job> = emptyList()
                subs.update { value ->
                    val (newValue, existingJobs) = value.removeListener(listenerId)
                    jobsToCancel = existingJobs
                    newValue
                }

                jobsToCancel.forEach { job -> job.cancel() }
            }
        }
    }

    data class ChannelSubscriptions(val subs: Map<String, Listeners> = emptyMap()) {
        fun removeListener(listenerId: String): Pair<ChannelSubscriptions, List<Job>> {
            val newMap = mutableMapOf<String, Listeners>()
            val jobsToCancel = mutableListOf<Job>()
            subs.forEach { (channelName, listeners) ->
                val (newListeners, jobToCancel) = listeners.removeListener(listenerId)
                jobToCancel?.let { jobsToCancel.add(it) }
                newMap[channelName] = newListeners
            }
            return ChannelSubscriptions(newMap) to jobsToCancel
        }

        fun addSubscription(channelName: String, listenerId: String, job: Job): Pair<ChannelSubscriptions, Job?> {
            val newMap = mutableMapOf<String, Listeners>()
            newMap.putAll(subs)
            val channelSubs = newMap[channelName] ?: Listeners()
            val (newChannelSubs, oldJob) = channelSubs.addListener(listenerId, job)
            newMap[channelName] = newChannelSubs
            return ChannelSubscriptions(newMap) to oldJob
        }

        fun removeSubscription(channelName: String, listenerId: String): Pair<ChannelSubscriptions, Job?> {
            val newMap = mutableMapOf<String, Listeners>()
            newMap.putAll(subs)
            val channelSubs = newMap[channelName] ?: Listeners()
            val (newChannelSubs, jobToCancel) = channelSubs.removeListener(listenerId)
            newMap[channelName] = newChannelSubs
            return ChannelSubscriptions(newMap) to jobToCancel
        }
    }

    data class Listeners(val byListenerId: Map<String, Job> = emptyMap()) {
        fun addListener(listenerId: String, job: Job): Pair<Listeners, Job?> {
            val newMap = mutableMapOf<String, Job>()
            val oldJob = byListenerId[listenerId]
            newMap.putAll(byListenerId)
            newMap[listenerId] = job
            return Listeners(newMap) to oldJob
        }

        fun removeListener(listenerId: String): Pair<Listeners, Job?> {
            val resultById = mutableMapOf<String, Job>()
            resultById.putAll(byListenerId)
            val jobToCancel = resultById.remove(listenerId)
            return Listeners(resultById) to jobToCancel
        }
    }

    companion object {
        const val EXTRA_CHANNEL_NAME = "channelName"
        const val EXTRA_SHOULD_START = "shouldStart"
        const val CHANNEL_NAME_ODDS = "odds"
        const val CHANNEL_NAME_EVENS = "evens"
        const val CHANNEL_NAME_FIBO = "fibo"

        fun createBindIntent(context: Context): Intent {
            return Intent(context, MyService::class.java)
        }

        fun createStartIntent(context: Context, channelName: String): Intent {
            return Intent(context, MyService::class.java).apply {
                putExtra(EXTRA_CHANNEL_NAME, channelName)
                putExtra(EXTRA_SHOULD_START, true)
            }
        }

        fun createStopIntent(context: Context, channelName: String): Intent {
            return Intent(context, MyService::class.java).apply {
                putExtra(EXTRA_CHANNEL_NAME, channelName)
                putExtra(EXTRA_SHOULD_START, false)
            }
        }
    }
}
