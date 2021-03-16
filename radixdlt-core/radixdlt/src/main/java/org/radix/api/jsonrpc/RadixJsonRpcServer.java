/*
 * (C) Copyright 2020 Radix DLT Ltd
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

package org.radix.api.jsonrpc;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.json.JSONObject;
import org.radix.api.jsonrpc.handler.AtomHandler;
import org.radix.api.jsonrpc.handler.HighLevelApiHandler;
import org.radix.api.jsonrpc.handler.LedgerHandler;
import org.radix.api.jsonrpc.handler.NetworkHandler;
import org.radix.api.jsonrpc.handler.SystemHandler;
import org.radix.api.services.AtomsService;
import org.radix.api.services.HighLevelApiService;
import org.radix.api.services.LedgerService;
import org.radix.api.services.NetworkService;
import org.radix.api.services.SystemService;

import com.google.common.io.CharStreams;
import com.google.inject.Inject;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

import io.undertow.server.HttpServerExchange;

import static org.radix.api.jsonrpc.JsonRpcUtil.INVALID_PARAMS;
import static org.radix.api.jsonrpc.JsonRpcUtil.PARSE_ERROR;
import static org.radix.api.jsonrpc.JsonRpcUtil.REQUEST_TOO_LONG;
import static org.radix.api.jsonrpc.JsonRpcUtil.SERVER_ERROR;
import static org.radix.api.jsonrpc.JsonRpcUtil.errorResponse;
import static org.radix.api.jsonrpc.JsonRpcUtil.jsonObject;
import static org.radix.api.jsonrpc.JsonRpcUtil.methodNotFoundResponse;

/**
 * Stateless Json Rpc 2.0 Server
 */
public final class RadixJsonRpcServer {
	private static final long DEFAULT_MAX_REQUEST_SIZE = 1024L * 1024L;
	private static final Logger logger = LogManager.getLogger();

	/**
	 * Maximum request size in bytes
	 */
	private final long maxRequestSizeBytes;

	/**
	 * Store to query atoms from
	 */
	private final Map<String, Function<JSONObject, JSONObject>> handlers = new HashMap<>();
	private final SystemHandler systemHandler;
	private final NetworkHandler networkHandler;
	private final AtomHandler atomHandler;
	private final LedgerHandler ledgerHandler;
	private final HighLevelApiHandler highLevelApiHandler;

	@Inject
	public RadixJsonRpcServer(
		SystemHandler systemHandler,
		NetworkHandler networkHandler,
		AtomHandler atomHandler,
		LedgerHandler ledgerHandler,
		HighLevelApiHandler highLevelApiHandler
	) {
		this(systemHandler, networkHandler, atomHandler, ledgerHandler, highLevelApiHandler, DEFAULT_MAX_REQUEST_SIZE);
	}

	public RadixJsonRpcServer(
		SystemHandler systemHandler,
		NetworkHandler networkHandler,
		AtomHandler atomHandler,
		LedgerHandler ledgerHandler,
		HighLevelApiHandler highLevelApiHandler,
		long maxRequestSizeBytes
	) {
		this.systemHandler = systemHandler;
		this.networkHandler = networkHandler;
		this.atomHandler = atomHandler;
		this.ledgerHandler = ledgerHandler;
		this.highLevelApiHandler = highLevelApiHandler;
		this.maxRequestSizeBytes = maxRequestSizeBytes;

		fillHandlers();
	}

	private void fillHandlers() {
		//BFT
		addHandler("BFT.start", systemHandler::handleBftStart);
		addHandler("BFT.stop", systemHandler::handleBftStop);

		//General info
		addHandler("Universe.getUniverse", systemHandler::handleGetUniverse);
		addHandler("Network.getInfo", systemHandler::handleGetLocalSystem);
		addHandler("Ping", systemHandler::handlePing);

		//Network info
		addHandler("Network.getLivePeers", networkHandler::handleGetLivePeers);
		addHandler("Network.getPeers", networkHandler::handleGetPeers);

		//Atom submission/retrieval
		//TODO: check and fix method naming?
		addHandler("Atoms.submitAtom", atomHandler::handleSubmitAtom);
		addHandler("Ledger.getAtom", atomHandler::handleGetAtom);

		//Ledger
		//TODO: check and fix method naming?
		addHandler("Atoms.getAtomStatus", ledgerHandler::handleGetAtomStatus);

		//High level API's
		addHandler("radix.universeMagic", highLevelApiHandler::handleUniverseMagic);
		addHandler("radix.nativeToken", highLevelApiHandler::handleNativeToken);
		addHandler("radix.tokenBalances", highLevelApiHandler::handleTokenBalances);
		addHandler("radix.executedTransactions", highLevelApiHandler::handleExecutedTransactions);
		addHandler("radix.transactionStatus", highLevelApiHandler::handleTransactionStatus);
	}

	private void addHandler(String methodName, Function<JSONObject, JSONObject> handler) {
		handlers.put(methodName, input -> {
			try {
				logger.debug("RPC: calling {} with {}", methodName, input);
				JSONObject object = handler.apply(input);
				logger.debug("RPC: returned from {}: ", methodName, object);
				return object;
			} finally {
				logger.debug("RPC: call to {} done", methodName);
			}
		});
	}

	/**
	 * Extract a JSON RPC API request from an HttpServerExchange, handle it as usual and return the response
	 *
	 * @param exchange The JSON RPC API request
	 *
	 * @return The response
	 */
	public String handleJsonRpc(HttpServerExchange exchange) {
		try {
			// Switch to blocking since we need to retrieve whole request body
			exchange.setMaxEntitySize(maxRequestSizeBytes);
			exchange.startBlocking();

			var requestBody = CharStreams.toString(new InputStreamReader(exchange.getInputStream(), StandardCharsets.UTF_8));
			return handleRpc(requestBody);
		} catch (IOException e) {
			throw new IllegalStateException("RPC failed", e);
		}
	}

	/**
	 * Handle the string JSON-RPC request with size checks, return appropriate error if size exceeds the limit.
	 *
	 * @param requestString The string JSON-RPC request
	 *
	 * @return The response to the request, could be a JSON-RPC error
	 */
	String handleRpc(String requestString) {
		int length = requestString.getBytes(StandardCharsets.UTF_8).length;

		if (length > maxRequestSizeBytes) {
			return errorResponse(REQUEST_TOO_LONG, "request too big: " + length + " > " + maxRequestSizeBytes).toString();
		}

		logger.debug("RPC: request: {}", requestString);

		return jsonObject(requestString)
			.map(this::handle)
			.map(Object::toString)
			.map(str -> {
				logger.debug("RPC: response: {}", str);
				return str;
			})
			.orElseGet(() -> errorResponse(PARSE_ERROR, "unable to parse input").toString());
	}

	private JSONObject handle(JSONObject request) {
		logger.debug("RPC: handling {}", request);
		if (!request.has("id")) {
			logger.debug("RPC: id missing");
			return errorResponse(INVALID_PARAMS, "id missing");
		}

		if (!request.has("method")) {
			logger.debug("RPC: method missing");
			return errorResponse(INVALID_PARAMS, "method missing");
		}

		try {
			return Optional.ofNullable(handlers.get(request.getString("method")))
				.map(handler -> {
					try {
						logger.debug("RPC: starting processing of {}", request);
						return handler.apply(request);
					} finally {
						logger.debug("RPC: processing done for {}", request);
					}
				})
				.orElseGet(() -> methodNotFoundResponse(request.get("id")));

		} catch (Exception e) {
			var id = request.get("id");
			if (request.has("params") && request.get("params") instanceof JSONObject) {
				return errorResponse(id, SERVER_ERROR, e.getMessage(), request.getJSONObject("params"));
			} else {
				return errorResponse(id, SERVER_ERROR, e.getMessage());
			}
		}
	}
}
