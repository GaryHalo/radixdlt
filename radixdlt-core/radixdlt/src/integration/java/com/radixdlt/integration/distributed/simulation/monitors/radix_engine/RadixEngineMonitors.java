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
 *
 */

package com.radixdlt.integration.distributed.simulation.monitors.radix_engine;

import com.google.inject.AbstractModule;
import com.google.inject.Module;
import com.google.inject.multibindings.ProvidesIntoMap;
import com.radixdlt.integration.distributed.simulation.Monitor;
import com.radixdlt.integration.distributed.simulation.MonitorKey;
import com.radixdlt.integration.distributed.simulation.TestInvariant;
import com.radixdlt.integration.distributed.simulation.monitors.EventNeverOccursInvariant;
import com.radixdlt.integration.distributed.simulation.monitors.NodeEvents;
import com.radixdlt.statecomputer.InvalidProposedCommand;

/**
 * Monitors which check for radix engine related functionality
 */
public final class RadixEngineMonitors {
	private RadixEngineMonitors() {
		throw new IllegalStateException("Cannot instantiate.");
	}

	public static Module noInvalidProposedCommands() {
		return new AbstractModule() {
			@ProvidesIntoMap
			@MonitorKey(Monitor.RADIX_ENGINE_NO_INVALID_PROPOSED_COMMANDS)
			TestInvariant registeredValidator(NodeEvents nodeEvents) {
				return new EventNeverOccursInvariant<>(nodeEvents, InvalidProposedCommand.class);
			}
		};
	}
}
