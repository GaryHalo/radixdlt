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

package com.radixdlt.consensus.simulation;

import com.radixdlt.middleware2.network.TestEventCoordinatorNetwork.LatencyProvider;
import com.radixdlt.middleware2.network.TestEventCoordinatorNetwork.MessageInTransit;
import java.util.Random;

/**
 * Latency Provider which uniformly distributes latency across a minimum and maximum
 */
public final class RandomLatencyProvider implements LatencyProvider {
	private final int minLatency;
	private final int maxLatency;
	private final Random rng;

	RandomLatencyProvider(int minLatency, int maxLatency) {
		if (minLatency < 0) {
			throw new IllegalArgumentException("minimumLatency must be >= 0 but was " + minLatency);
		}
		if (maxLatency < 0) {
			throw new IllegalArgumentException("maximumLatency must be >= 0 but was " + maxLatency);
		}
		this.rng = new Random(System.currentTimeMillis());
		this.minLatency = minLatency;
		this.maxLatency = maxLatency;
	}

	@Override
	public int nextLatency(MessageInTransit msg) {
		return minLatency + rng.nextInt(maxLatency - minLatency + 1);
	}
}
