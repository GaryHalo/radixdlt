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

package org.radix.api.jsonrpc.handler;

import org.json.JSONObject;
import org.radix.api.services.NetworkService;

import com.google.inject.Inject;

import java.util.List;

import static org.radix.api.jsonrpc.JsonRpcUtil.jsonArray;
import static org.radix.api.jsonrpc.JsonRpcUtil.response;

public class NetworkHandler {
	private final NetworkService networkService;

	@Inject
	public NetworkHandler(final NetworkService networkService) {
		this.networkService = networkService;
	}

	public JSONObject handleGetLivePeers(JSONObject request) {
		return respond(request, networkService.getLivePeers());
	}

	public JSONObject handleGetPeers(JSONObject request) {
		return respond(request, networkService.getPeers());
	}

	private JSONObject respond(final JSONObject request, final List<JSONObject> peers) {
		var result = jsonArray();
		peers.forEach(result::put);

		return response(request, result);
	}
}
