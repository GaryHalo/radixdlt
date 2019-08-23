package org.radix.network.peers;

import com.google.common.annotations.VisibleForTesting;
import com.radixdlt.serialization.Polymorphic;
import com.radixdlt.serialization.SerializerId2;
import org.radix.logging.Logger;
import org.radix.logging.Logging;
import org.radix.modules.Modules;
import org.radix.network.Protocol;
import org.radix.network.messaging.Message;
import org.radix.network2.messaging.MessageCentral;

import java.net.URI;

@SerializerId2("network.peer")
public class UDPPeer extends Peer implements Polymorphic
{
	private static final Logger networkLog = Logging.getLogger("network");

	// Used by serializer
	UDPPeer()
	{
		super();
	}

	public UDPPeer(URI host, Peer peer) {
		super(host, peer);

		connect();

		networkLog.debug("Connection opened on "+toString());
	}

	@VisibleForTesting
	public UDPPeer(Void doNotUseThisConstructor, URI host) {
		super(host);
	}

	@Override
	public String toString()
	{
		return "UDP "+super.toString();
	}

	@Override
	public void send(Message message) {
		Modules.get(MessageCentral.class).send(this, message);
	}

	private void connect() {
		onConnecting();
		onConnected();
	}

	@Override
	void onConnected() {
		super.onConnected();
		addProtocol(Protocol.UDP);
	}
}
