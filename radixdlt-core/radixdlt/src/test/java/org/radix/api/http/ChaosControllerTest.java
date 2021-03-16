/*
 * (C) Copyright 2021 Radix DLT Ltd
 *
 * Radix DLT Ltd licenses this file to you under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except in
 * compliance with the License.  You may obtain a copy of the
 * License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied.  See the License for the specific
 * language governing permissions and limitations under the License.
 */
package org.radix.api.http;

import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.stubbing.Answer;

import com.radixdlt.chaos.mempoolfiller.InMemoryWallet;
import com.radixdlt.chaos.mempoolfiller.MempoolFillerUpdate;
import com.radixdlt.chaos.messageflooder.MessageFlooderUpdate;
import com.radixdlt.crypto.ECKeyPair;
import com.radixdlt.engine.RadixEngine;
import com.radixdlt.environment.EventDispatcher;
import com.radixdlt.identifiers.RadixAddress;
import com.radixdlt.middleware2.LedgerAtom;
import com.radixdlt.utils.Base58;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import io.undertow.io.Sender;
import io.undertow.server.HttpServerExchange;
import io.undertow.server.RoutingHandler;
import io.undertow.util.HeaderMap;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class ChaosControllerTest {
	private final RadixEngine<LedgerAtom> radixEngine = mock(RadixEngine.class);
	private final Optional<RadixAddress> emptyMempoolFillerAddress = Optional.empty();
	private final Optional<RadixAddress> mempoolFillerAddress = Optional.of(
		RadixAddress.from("23B6fH3FekJeP6e5guhZAk6n9z4fmTo5Tngo3a11Wg5R8gsWTV2x")
	);
	private final EventDispatcher<MempoolFillerUpdate> mempool = mock(EventDispatcher.class);
	private final EventDispatcher<MessageFlooderUpdate> message = mock(EventDispatcher.class);

	@Test
	public void routesAreConfigured() {
		final ChaosController chaosController = new ChaosController(mempoolFillerAddress, radixEngine, mempool, message);
		var handler = mock(RoutingHandler.class);
		chaosController.configureRoutes(handler);

		verify(handler).put(eq("/api/chaos/message-flooder"), any());
		verify(handler).put(eq("/api/chaos/mempool-filler"), any());
		verify(handler).get(eq("/api/chaos/mempool-filler"), any());
	}

	@Test
	public void testHandleMessageFlood() throws InterruptedException {
		final ChaosController chaosController = new ChaosController(mempoolFillerAddress, radixEngine, mempool, message);
		var latch = new CountDownLatch(1);
		var arg = new AtomicReference<String>();

		String nodeKey = Base58.toBase58(ECKeyPair.generateNew().getPublicKey().getBytes());
		var exchange = createExchange(
			"{ \"enabled\" : true, \"data\" : { \"nodeKey\" : \""
				+ nodeKey
				+ "\", \"messagesPerSec\" : 10, \"commandSize\" : 123 }}",
			invocation -> {
				arg.set(invocation.getArgument(0, String.class));
				latch.countDown();
				return null;
			}
		);

		chaosController.handleMessageFlood(exchange);

		latch.await();
		assertEquals("{}", arg.get());

		var captor = ArgumentCaptor.forClass(MessageFlooderUpdate.class);
		verify(message).dispatch(captor.capture());

		var value = captor.getValue();
		assertEquals(Optional.of(10), value.getMessagesPerSec());
		assertEquals(Optional.of(123), value.getCommandSize());
	}

	@Test
	public void testHandleMempoolFill() throws InterruptedException {
		final ChaosController chaosController = new ChaosController(mempoolFillerAddress, radixEngine, mempool, message);
		var latch = new CountDownLatch(1);
		var arg = new AtomicReference<String>();

		String nodeKey = Base58.toBase58(ECKeyPair.generateNew().getPublicKey().getBytes());
		var exchange = createExchange(
			"{ \"enabled\" : true}",
			invocation -> {
				arg.set(invocation.getArgument(0, String.class));
				latch.countDown();
				return null;
			}
		);

		chaosController.handleMempoolFill(exchange);

		latch.await();
		assertEquals("{}", arg.get());

		var captor = ArgumentCaptor.forClass(MempoolFillerUpdate.class);
		verify(mempool).dispatch(captor.capture());
		assertTrue(captor.getValue().enabled());
	}

	@Test
	public void testRespondWithMempoolFillAddressWhenAddressIsAvailable() throws InterruptedException {
		final ChaosController chaosController = new ChaosController(mempoolFillerAddress, radixEngine, mempool, message);
		var latch = new CountDownLatch(1);
		var arg = new AtomicReference<String>();

		var exchange = createExchange(
			"",
			invocation -> {
				arg.set(invocation.getArgument(0, String.class));
				latch.countDown();
				return null;
			}
		);

		var wallet = mock(InMemoryWallet.class);
		when(radixEngine.getComputedState(InMemoryWallet.class)).thenReturn(wallet);
		when(wallet.getBalance()).thenReturn(BigDecimal.ONE);
		when(wallet.getNumParticles()).thenReturn(7);

		chaosController.respondWithMempoolFill(exchange);

		latch.await();
		assertEquals(
			"{\"address\":\"23B6fH3FekJeP6e5guhZAk6n9z4fmTo5Tngo3a11Wg5R8gsWTV2x\",\"balance\":1,\"numParticles\":7}",
			arg.get()
		);
	}

	@Test
	public void testRespondWithMempoolFillAddressWhenAddressIsNotAvailable() throws InterruptedException {
		final ChaosController chaosController = new ChaosController(emptyMempoolFillerAddress, radixEngine, mempool, message);

		var latch = new CountDownLatch(1);
		var arg = new AtomicReference<String>();

		String nodeKey = Base58.toBase58(ECKeyPair.generateNew().getPublicKey().getBytes());
		var exchange = createExchange(
			"",
			invocation -> {
				arg.set(invocation.getArgument(0, String.class));
				latch.countDown();
				return null;
			}
		);

		chaosController.respondWithMempoolFill(exchange);

		latch.await();
		assertEquals("Mempool filler address is not configured", arg.get());
	}

	private static HttpServerExchange createExchange(final String json, final Answer<Void> answer) {
		var exchange = mock(HttpServerExchange.class);
		var sender = mock(Sender.class);

		doAnswer(answer).when(sender).send(anyString());
		when(exchange.getResponseHeaders()).thenReturn(mock(HeaderMap.class));
		when(exchange.getResponseSender()).thenReturn(sender);
		when(exchange.getInputStream()).thenReturn(asStream(json));
		when(exchange.isInIoThread()).thenReturn(true);
		when(exchange.isResponseStarted()).thenReturn(false);
		when(exchange.dispatch(any(Runnable.class))).thenAnswer(invocation -> {
			var runnable = invocation.getArgument(0, Runnable.class);
			runnable.run();
			return exchange;
		});

		return exchange;
	}

	private static InputStream asStream(final String text) {
		return new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8));
	}
}