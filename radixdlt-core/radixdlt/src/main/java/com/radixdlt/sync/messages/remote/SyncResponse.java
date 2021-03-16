/*
 * (C) Copyright 2021 Radix DLT Ltd
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

package com.radixdlt.sync.messages.remote;

import com.radixdlt.ledger.DtoCommandsAndProof;

import java.util.Objects;

/**
 * A response to the SyncRequest message.
 */
public final class SyncResponse {

	private final DtoCommandsAndProof commandsAndProof;

	public static SyncResponse create(DtoCommandsAndProof commandsAndProof) {
		return new SyncResponse(commandsAndProof);
	}

	private SyncResponse(DtoCommandsAndProof commandsAndProof) {
		this.commandsAndProof = Objects.requireNonNull(commandsAndProof);
	}

	public DtoCommandsAndProof getCommandsAndProof() {
		return commandsAndProof;
	}

	@Override
	public String toString() {
		return String.format("%s{commandsAndProof=%s}", this.getClass().getSimpleName(), commandsAndProof);
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) {
			return true;
		}
		if (!(o instanceof SyncResponse)) {
			return false;
		}
		SyncResponse that = (SyncResponse) o;
		return Objects.equals(commandsAndProof, that.commandsAndProof);
	}

	@Override
	public int hashCode() {
		return Objects.hash(commandsAndProof);
	}
}
