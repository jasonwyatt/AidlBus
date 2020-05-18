package us.jwf.aidlbus

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import android.widget.Switch
import kotlinx.atomicfu.atomic
import kotlinx.atomicfu.updateAndGet
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.ConflatedBroadcastChannel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

@Suppress("EXPERIMENTAL_API_USAGE")
class MainActivity : AppCompatActivity() {
  private val scope = MainScope()
  private val oddsStates = callbackFlow {
    findViewById<Switch>(R.id.oddsToggle).also { offer(MyService.CHANNEL_NAME_ODDS to it.isChecked) }
      .setOnCheckedChangeListener { _, isChecked -> offer(MyService.CHANNEL_NAME_ODDS to isChecked) }
    awaitClose { }
  }
  private val evensStates = callbackFlow {
    findViewById<Switch>(R.id.evensToggle).also { offer(MyService.CHANNEL_NAME_EVENS to it.isChecked) }
      .setOnCheckedChangeListener { _, isChecked -> offer(MyService.CHANNEL_NAME_EVENS to isChecked) }
    awaitClose { }
  }
  private val fiboStates = callbackFlow {
    findViewById<Switch>(R.id.fiboToggle).also { offer(MyService.CHANNEL_NAME_FIBO to it.isChecked) }
      .setOnCheckedChangeListener { _, isChecked -> offer(MyService.CHANNEL_NAME_FIBO to isChecked) }
    awaitClose { }
  }
  private val oddsSubStates = callbackFlow {
    findViewById<Switch>(R.id.oddsSubToggle).also { offer(MyService.CHANNEL_NAME_ODDS to it.isChecked) }
      .setOnCheckedChangeListener { _, isChecked -> offer(MyService.CHANNEL_NAME_ODDS to isChecked) }
    awaitClose { }
  }
  private val evensSubStates = callbackFlow {
    findViewById<Switch>(R.id.evensSubToggle).also { offer(MyService.CHANNEL_NAME_EVENS to it.isChecked) }
      .setOnCheckedChangeListener { _, isChecked -> offer(MyService.CHANNEL_NAME_EVENS to isChecked) }
    awaitClose { }
  }
  private val fiboSubStates = callbackFlow {
    findViewById<Switch>(R.id.fiboSubToggle).also { offer(MyService.CHANNEL_NAME_FIBO to it.isChecked) }
      .setOnCheckedChangeListener { _, isChecked -> offer(MyService.CHANNEL_NAME_FIBO to isChecked) }
    awaitClose { }
  }
  private val publishingStates = merge(oddsStates, evensStates, fiboStates)
  private val subscriptionStates = merge(oddsSubStates, evensSubStates, fiboSubStates)
  private val eventBus = CompletableDeferred<IEventBus>()
  private val listenerId = CompletableDeferred<String>()
  private var events = atomic<List<EventBusProtos.Event>>(emptyList())
  private var eventsChannel = ConflatedBroadcastChannel<List<EventBusProtos.Event>>()
  private val listener = object : IEventListener.Stub() {
    override fun onEvent(serializedEventProto: ByteArray?) {
      val newEvents = events.updateAndGet {
        it + EventBusProtos.Event.parseFrom(serializedEventProto)
      }
      eventsChannel.offer(newEvents)
    }
  }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setContentView(R.layout.activity_main)

    publishingStates
      .onEach { (type, start) ->
        val intent =
          if (start) MyService.createStartIntent(this, type)
          else MyService.createStopIntent(this, type)
        startService(intent)
      }
      .launchIn(scope)

    subscriptionStates
      .buffer()
      .onEach { (type, start) ->
        if (start) {
          eventBus.await().subscribeListenerTo(listenerId.await(), type)
        } else {
          eventBus.await().unsubscribeListenerFrom(listenerId.await(), type)
        }
      }
      .launchIn(scope)
    eventsChannel.asFlow()
      .onEach { Log.i("Events", it.lastOrNull().toString()) }
      .launchIn(scope)

    bindService(MyService.createBindIntent(this), serviceConnnection, Context.BIND_AUTO_CREATE)

    scope.launch {
      listenerId.complete(eventBus.await().registerListener(listener))
    }
  }

  override fun onDestroy() {
    super.onDestroy()
    unbindService(serviceConnnection)
    scope.cancel()
  }

  private val serviceConnnection = object : ServiceConnection {
    override fun onServiceDisconnected(name: ComponentName?) {
    }

    override fun onServiceConnected(name: ComponentName?, service: IBinder) {
        eventBus.complete(IEventBus.Stub.asInterface(service))
    }
  }
}
