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

package com.radixdlt.atom;

import com.google.common.collect.ImmutableList;
import com.radixdlt.DefaultSerialization;
import com.radixdlt.constraintmachine.CMMicroInstruction;
import com.radixdlt.constraintmachine.Particle;
import com.radixdlt.constraintmachine.Spin;
import com.radixdlt.crypto.HashUtils;
import com.radixdlt.serialization.DsonOutput;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;

/**
 * A group of particles representing one action, e.g. a transfer.
 */
public final class ParticleGroup {
	/**
	 * The particles and their spin contained within this {@link ParticleGroup}.
	 */
	private ImmutableList<CMMicroInstruction> instructions;

	private ParticleGroup(Iterable<CMMicroInstruction> instructions) {
		Objects.requireNonNull(instructions, "particles is required");

		this.instructions = ImmutableList.copyOf(instructions);
	}

	public List<CMMicroInstruction> getInstructions() {
		return instructions;
	}

	/**
	 * Get a stream of particles of a certain spin in this group
	 * @return The particles in this group with that spin
	 */
	public Stream<Particle> upParticles() {
		return this.instructions.stream()
			.filter(i -> i.getNextSpin() == Spin.UP)
			.map(CMMicroInstruction::getParticle);
	}

	public static ParticleGroupBuilder builder() {
		return new ParticleGroupBuilder();
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) {
			return true;
		}
		if (o == null || getClass() != o.getClass()) {
			return false;
		}
		ParticleGroup that = (ParticleGroup) o;
		return Objects.equals(instructions, that.instructions);
	}

	@Override
	public int hashCode() {
		return Objects.hash(instructions);
	}

	/**
	 * A builder for immutable {@link ParticleGroup}s
	 */
	public static class ParticleGroupBuilder {
		private List<CMMicroInstruction> instructions = new ArrayList<>();

		private ParticleGroupBuilder() {
		}

		public final ParticleGroupBuilder spinUp(Particle particle) {
			Objects.requireNonNull(particle, "particle is required");
			this.instructions.add(CMMicroInstruction.spinUp(particle));
			return this;
		}

		public final ParticleGroupBuilder virtualSpinDown(Particle particle) {
			Objects.requireNonNull(particle, "particle is required");
			this.instructions.add(CMMicroInstruction.virtualSpinDown(particle));
			return this;
		}

		public final ParticleGroupBuilder spinDown(Particle particle) {
			Objects.requireNonNull(particle, "particle is required");
			var dson = DefaultSerialization.getInstance().toDson(particle, DsonOutput.Output.ALL);
			var particleHash = HashUtils.sha256(dson);
			this.instructions.add(CMMicroInstruction.spinDown(particleHash));
			return this;
		}

		public ParticleGroup build() {
			return new ParticleGroup(ImmutableList.copyOf(this.instructions));
		}
	}
}
