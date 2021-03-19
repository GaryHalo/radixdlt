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

package com.radixdlt.constraintmachine;

import com.google.common.hash.HashCode;
import com.radixdlt.DefaultSerialization;
import com.radixdlt.crypto.HashUtils;
import com.radixdlt.serialization.DsonOutput;

import java.util.Objects;

public final class CMMicroInstruction {
	public enum CMMicroOp {
		CHECK_NEUTRAL_THEN_UP,
		CHECK_UP_THEN_DOWN,
		PARTICLE_GROUP
	}

	private final CMMicroOp operation;
	private final Particle particle;
	private final HashCode particleHash;

	private CMMicroInstruction(CMMicroOp operation, Particle particle, HashCode particleHash) {
		this.operation = operation;
		this.particle = particle;
		this.particleHash = particleHash;
	}

	public CMMicroOp getMicroOp() {
		return operation;
	}

	public Particle getParticle() {
		return particle;
	}

	public HashCode getParticleHash() {
		return particleHash;
	}

	public boolean isPush() {
		return operation == CMMicroOp.CHECK_UP_THEN_DOWN || operation == CMMicroOp.CHECK_NEUTRAL_THEN_UP;
	}

	public boolean isCheckSpin() {
		return operation == CMMicroOp.CHECK_UP_THEN_DOWN || operation == CMMicroOp.CHECK_NEUTRAL_THEN_UP;
	}

	public Spin getCheckSpin() {
		if (operation == CMMicroOp.CHECK_NEUTRAL_THEN_UP) {
			return Spin.NEUTRAL;
		} else if (operation == CMMicroOp.CHECK_UP_THEN_DOWN) {
			return Spin.UP;
		} else {
			throw new UnsupportedOperationException(operation + " is not a check spin operation.");
		}
	}

	public Spin getNextSpin() {
		if (operation == CMMicroOp.CHECK_NEUTRAL_THEN_UP) {
			return Spin.UP;
		} else if (operation == CMMicroOp.CHECK_UP_THEN_DOWN) {
			return Spin.DOWN;
		} else {
			throw new UnsupportedOperationException(operation + " is not a check spin operation.");
		}
	}

	public static CMMicroInstruction spinDown(HashCode particleHash) {
		return new CMMicroInstruction(CMMicroOp.CHECK_UP_THEN_DOWN, null, particleHash);
	}

	public static CMMicroInstruction virtualSpinDown(Particle particle) {
		return new CMMicroInstruction(CMMicroOp.CHECK_UP_THEN_DOWN, particle, null);
	}

	public static CMMicroInstruction spinUp(Particle particle) {
		return new CMMicroInstruction(CMMicroOp.CHECK_NEUTRAL_THEN_UP, particle, null);
	}

	public static CMMicroInstruction checkSpinAndPush(Particle particle, Spin spin) {
		if (spin == Spin.NEUTRAL) {
			return new CMMicroInstruction(CMMicroOp.CHECK_NEUTRAL_THEN_UP, particle, null);
		} else if (spin == Spin.UP) {
			return new CMMicroInstruction(CMMicroOp.CHECK_UP_THEN_DOWN, particle, null);
		} else {
			throw new IllegalStateException("Invalid check spin: " + spin);
		}
	}

	public static CMMicroInstruction particleGroup() {
		return new CMMicroInstruction(CMMicroOp.PARTICLE_GROUP, null, null);
	}

	@Override
	public String toString() {
		return String.format("%s %s",
			operation,
			particle != null
				? HashUtils.sha256(DefaultSerialization.getInstance().toDson(particle, DsonOutput.Output.ALL)) + ":" + particle
				: particleHash
		);
	}

	@Override
	public int hashCode() {
		return Objects.hash(operation, particle, particleHash);
	}

	@Override
	public boolean equals(Object o) {
		if (!(o instanceof CMMicroInstruction)) {
			return false;
		}

		var other = (CMMicroInstruction) o;
		return Objects.equals(this.operation, other.operation)
			&& Objects.equals(this.particle, other.particle)
			&& Objects.equals(this.particleHash, other.particleHash);
	}
}
