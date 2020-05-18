// IEventBus.aidl
package us.jwf.aidlbus;

import us.jwf.aidlbus.IEventListener;

interface IEventBus {
    String registerListener(in IEventListener listener);
    void subscribeListenerTo(String listenerId, String channel);
    void unsubscribeListenerFrom(String listenerId, String channel);
    void unregisterListener(String listenerId);
}
