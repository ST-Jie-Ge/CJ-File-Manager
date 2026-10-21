package com.rapid7.client.dcerpc;

import java.io.IOException;

/** Android has no java.rmi; RPC decoding failures retain their IOException contract. */
public final class AndroidUnmarshalException extends IOException {
    public AndroidUnmarshalException(String message) { super(message); }
    public AndroidUnmarshalException(String message, Exception cause) { super(message, cause); }
}
