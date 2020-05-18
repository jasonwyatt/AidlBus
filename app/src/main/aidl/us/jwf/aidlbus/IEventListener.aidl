// IEventListener.aidl
package us.jwf.aidlbus;

// Declare any non-default types here with import statements

interface IEventListener {
    oneway void onEvent(in byte[] serializedEventProto);
}
