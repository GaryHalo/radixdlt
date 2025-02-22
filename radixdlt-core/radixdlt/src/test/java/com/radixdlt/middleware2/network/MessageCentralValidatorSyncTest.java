/*
 * (C) Copyright 2020 Radix DLT Ltd
 *
 * Radix DLT Ltd licenses this file to you under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except in
 * compliance with the License.  You may obtain a copy of the
 * License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied.  See the License for the specific
 * language governing permissions and limitations under the License.
 */

package com.radixdlt.middleware2.network;

import static org.assertj.core.api.AssertionsForClassTypes.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.common.collect.ImmutableList;
import com.google.common.hash.HashCode;
import com.radixdlt.consensus.sync.GetVerticesErrorResponse;
import com.radixdlt.consensus.sync.GetVerticesResponse;
import com.radixdlt.crypto.ECKeyPair;
import com.radixdlt.crypto.Hasher;
import com.radixdlt.consensus.QuorumCertificate;
import com.radixdlt.consensus.HighQC;
import com.radixdlt.consensus.bft.BFTNode;
import com.radixdlt.consensus.UnverifiedVertex;
import com.radixdlt.consensus.bft.VerifiedVertex;
import com.radixdlt.consensus.sync.GetVerticesRequest;
import com.radixdlt.crypto.ECPublicKey;
import com.radixdlt.crypto.HashUtils;
import com.radixdlt.environment.rx.RemoteEvent;
import com.radixdlt.identifiers.EUID;
import com.radixdlt.network.addressbook.AddressBook;
import com.radixdlt.network.addressbook.Peer;
import com.radixdlt.network.addressbook.PeerWithSystem;
import com.radixdlt.network.messaging.MessageCentral;
import com.radixdlt.network.messaging.MessageCentralMockProvider;

import java.util.Optional;

import com.radixdlt.utils.RandomHasher;
import io.reactivex.rxjava3.subscribers.TestSubscriber;
import org.junit.Before;
import org.junit.Test;
import org.radix.universe.system.RadixSystem;

public class MessageCentralValidatorSyncTest {
	private BFTNode self;
	private AddressBook addressBook;
	private MessageCentral messageCentral;
	private MessageCentralValidatorSync sync;
	private Hasher hasher;

	@Before
	public void setUp() {
		this.self = mock(BFTNode.class);
		EUID selfEUID = mock(EUID.class);
		ECPublicKey pubKey = mock(ECPublicKey.class);
		when(pubKey.euid()).thenReturn(selfEUID);
		when(self.getKey()).thenReturn(pubKey);
		this.addressBook = mock(AddressBook.class);
		this.messageCentral = MessageCentralMockProvider.get();
		this.hasher = new RandomHasher();
		this.sync = new MessageCentralValidatorSync(self, 0, addressBook, messageCentral, hasher);
	}

	@Test
	public void when_send_rpc_to_self__then_illegal_state_exception_should_be_thrown() {
		assertThatThrownBy(() -> sync.sendGetVerticesRequest(self, mock(GetVerticesRequest.class)))
			.isInstanceOf(IllegalStateException.class);
	}

	@Test
	public void when_get_vertex_and_peer_doesnt_exist__no_request_sent() {
		BFTNode node = mock(BFTNode.class);
		ECPublicKey key = mock(ECPublicKey.class);
		EUID euid = mock(EUID.class);
		when(key.euid()).thenReturn(euid);
		when(node.getKey()).thenReturn(key);
		when(addressBook.peer(euid)).thenReturn(Optional.empty());
		sync.sendGetVerticesRequest(node, mock(GetVerticesRequest.class));

		// Some attempt was made to discover peer
		verify(this.addressBook, times(1)).peer(any(EUID.class));

		// No messages sent or injected
		verify(this.messageCentral, never()).send(any(), any());
	}

	@Test
	public void when_send_response__then_message_central_will_send_response() {
		VerifiedVertex vertex = mock(VerifiedVertex.class);
		when(vertex.toSerializable()).thenReturn(mock(UnverifiedVertex.class));
		ImmutableList<VerifiedVertex> vertices = ImmutableList.of(vertex);

		BFTNode node = mock(BFTNode.class);
		ECPublicKey ecPublicKey = mock(ECPublicKey.class);
		when(ecPublicKey.euid()).thenReturn(mock(EUID.class));
		when(node.getKey()).thenReturn(ecPublicKey);
		when(addressBook.peer(any(EUID.class))).thenReturn(Optional.of(mock(PeerWithSystem.class)));

		sync.sendGetVerticesResponse(node, vertices);
		verify(messageCentral, times(1)).send(any(), any(GetVerticesResponseMessage.class));
	}

	@Test
	public void when_send_error_response__then_message_central_will_send_error_response() {
		PeerWithSystem peer = mock(PeerWithSystem.class);
		QuorumCertificate qc = mock(QuorumCertificate.class);
		HighQC highQC = mock(HighQC.class);
		when(highQC.highestQC()).thenReturn(qc);
		when(highQC.highestCommittedQC()).thenReturn(qc);
		BFTNode node = mock(BFTNode.class);
		ECPublicKey ecPublicKey = mock(ECPublicKey.class);
		when(ecPublicKey.euid()).thenReturn(mock(EUID.class));
		when(node.getKey()).thenReturn(ecPublicKey);
		when(addressBook.peer(any(EUID.class))).thenReturn(Optional.of(peer));
		final var request = new GetVerticesRequest(HashUtils.random256(), 3);

		sync.sendGetVerticesErrorResponse(node, highQC, request);

		verify(messageCentral, times(1)).send(eq(peer), any(GetVerticesErrorResponseMessage.class));
	}

	@Test
	public void when_subscribed_to_rpc_requests__then_should_receive_requests() {
		Peer peer = mock(Peer.class);
		when(peer.hasSystem()).thenReturn(true);
		RadixSystem system = mock(RadixSystem.class);
		when(system.getKey()).thenReturn(ECKeyPair.generateNew().getPublicKey());
		when(peer.getSystem()).thenReturn(system);
		HashCode vertexId0 = mock(HashCode.class);
		HashCode vertexId1 = mock(HashCode.class);

		TestSubscriber<GetVerticesRequest> testObserver = sync.requests().map(RemoteEvent::getEvent).test();
		messageCentral.send(peer, new GetVerticesRequestMessage(0, vertexId0, 1));
		messageCentral.send(peer, new GetVerticesRequestMessage(0, vertexId1, 1));

		testObserver.awaitCount(2);
		testObserver.assertValueAt(0, v -> v.getVertexId().equals(vertexId0));
		testObserver.assertValueAt(1, v -> v.getVertexId().equals(vertexId1));
	}

	@Test
	public void when_subscribed_to_rpc_responses__then_should_receive_responses() {
		Peer peer = mock(Peer.class);
		when(peer.hasSystem()).thenReturn(true);
		RadixSystem system = mock(RadixSystem.class);
		when(system.getKey()).thenReturn(ECKeyPair.generateNew().getPublicKey());
		when(peer.getSystem()).thenReturn(system);
		UnverifiedVertex vertex1 = mock(UnverifiedVertex.class);

		TestSubscriber<GetVerticesResponse> testObserver = sync.responses().test();
		messageCentral.send(peer, new GetVerticesResponseMessage(0, ImmutableList.of(vertex1)));
		messageCentral.send(peer, new GetVerticesResponseMessage(0, ImmutableList.of()));

		testObserver.awaitCount(2);
		testObserver.assertValueAt(0, v -> v.getVertices().size() == 1);
		testObserver.assertValueAt(1, v -> v.getVertices().isEmpty());
	}

	@Test
	public void when_subscribed_to_rpc_error_responses__then_should_receive_error_responses() {
		Peer peer = mock(Peer.class);
		when(peer.hasSystem()).thenReturn(true);
		RadixSystem system = mock(RadixSystem.class);
		when(system.getKey()).thenReturn(ECKeyPair.generateNew().getPublicKey());
		when(peer.getSystem()).thenReturn(system);
		final var highQc1 = mock(HighQC.class);
		final var highQc2 = mock(HighQC.class);

		TestSubscriber<GetVerticesErrorResponse> testObserver = sync.errorResponses().test();

		var requestMessage = new GetVerticesRequestMessage(0, HashUtils.random256(), 1);
		messageCentral.send(peer, new GetVerticesErrorResponseMessage(0, highQc1, requestMessage));
		messageCentral.send(peer, new GetVerticesErrorResponseMessage(0, highQc2, requestMessage));

		testObserver.awaitCount(2);
		testObserver.assertValueAt(0, v -> v.highQC().equals(highQc1));
		testObserver.assertValueAt(1, v -> v.highQC().equals(highQc2));
	}
}
