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

package com.radixdlt.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.radixdlt.atommodel.system.SystemConstraintScrypt;
import com.radixdlt.atommodel.system.SystemParticle;
import com.radixdlt.atomos.CMAtomOS;
import com.radixdlt.constraintmachine.CMErrorCode;
import com.radixdlt.constraintmachine.CMInstruction;
import com.radixdlt.constraintmachine.CMMicroInstruction;
import com.radixdlt.constraintmachine.ConstraintMachine;
import com.radixdlt.constraintmachine.PermissionLevel;
import com.radixdlt.constraintmachine.Spin;
import com.radixdlt.crypto.HashUtils;
import com.radixdlt.store.EngineStore;
import com.radixdlt.store.InMemoryEngineStore;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;

import java.util.List;

/**
 * Tests BFT System verification logic
 */
public class SystemTest {
	private RadixEngine<RadixEngineAtom, Void> engine;
	private EngineStore<RadixEngineAtom, Void> store;

	@Before
	public void setup() {
		CMAtomOS cmAtomOS = new CMAtomOS();
		cmAtomOS.load(new SystemConstraintScrypt());
		ConstraintMachine cm = new ConstraintMachine.Builder()
			.setParticleStaticCheck(cmAtomOS.buildParticleStaticCheck())
			.setParticleTransitionProcedures(cmAtomOS.buildTransitionProcedures())
			.build();
		this.store = new InMemoryEngineStore<>();
		this.engine = new RadixEngine<>(
			cm,
			cmAtomOS.virtualizedUpParticles(),
			store
		);
	}

	@Test
	public void executing_system_update_without_permissions_should_fail() {
		// Arrange
		SystemParticle systemParticle = new SystemParticle(0, 0, 0);
		SystemParticle nextSystemParticle = new SystemParticle(0, 1, 1);
		ImmutableList<CMMicroInstruction> instructions = ImmutableList.of(
			CMMicroInstruction.virtualSpinDown(systemParticle),
			CMMicroInstruction.spinUp(nextSystemParticle),
			CMMicroInstruction.particleGroup()
		);
		CMInstruction instruction = new CMInstruction(
			instructions,
			ImmutableMap.of()
		);

		// Act
		// Assert
		assertThatThrownBy(() -> this.engine.execute(List.of(new BaseAtom(instruction, HashUtils.zero256()))))
			.isInstanceOf(RadixEngineException.class)
			.extracting(e -> ((RadixEngineException) e).getCmError().getErrorCode())
			.isEqualTo(CMErrorCode.INVALID_EXECUTION_PERMISSION);
	}

	@Test
	public void executing_system_update_with_correct_permissions_should_succeed() throws RadixEngineException {
		// Arrange
		SystemParticle systemParticle = new SystemParticle(0, 0, 0);
		SystemParticle nextSystemParticle = new SystemParticle(0, 1, 1);
		ImmutableList<CMMicroInstruction> instructions = ImmutableList.of(
			CMMicroInstruction.virtualSpinDown(systemParticle),
			CMMicroInstruction.spinUp(nextSystemParticle),
			CMMicroInstruction.particleGroup()
		);
		CMInstruction instruction = new CMInstruction(
			instructions,
			ImmutableMap.of()
		);

		// Act
		this.engine.execute(List.of(new BaseAtom(instruction, HashUtils.zero256())), null, PermissionLevel.SUPER_USER);

		// Assert
		assertThat(this.store.getSpin(null, nextSystemParticle)).isEqualTo(Spin.UP);
	}

	@Test
	public void executing_system_update_with_bad_epoch_should_fail() {
		SystemParticle systemParticle = new SystemParticle(0, 0, 0);
		SystemParticle nextSystemParticle = new SystemParticle(-1, 1, 1);
		ImmutableList<CMMicroInstruction> instructions = ImmutableList.of(
			CMMicroInstruction.virtualSpinDown(systemParticle),
			CMMicroInstruction.spinUp(nextSystemParticle),
			CMMicroInstruction.particleGroup()
		);
		CMInstruction instruction = new CMInstruction(
			instructions,
			ImmutableMap.of()
		);

		var atom = new BaseAtom(instruction, HashUtils.zero256());

		assertThatThrownBy(() -> this.engine.execute(List.of(atom), null, PermissionLevel.SUPER_USER))
			.isInstanceOf(RadixEngineException.class)
			.extracting(e -> ((RadixEngineException) e).getCmError().getErrorCode())
			.isEqualTo(CMErrorCode.INVALID_PARTICLE);
	}

	@Test
	public void executing_system_update_with_bad_view_should_fail() {
		SystemParticle systemParticle = new SystemParticle(0, 0, 0);
		SystemParticle nextSystemParticle = new SystemParticle(0, -1, 1);
		ImmutableList<CMMicroInstruction> instructions = ImmutableList.of(
			CMMicroInstruction.virtualSpinDown(systemParticle),
			CMMicroInstruction.spinUp(nextSystemParticle),
			CMMicroInstruction.particleGroup()
		);
		CMInstruction instruction = new CMInstruction(
			instructions,
			ImmutableMap.of()
		);

		var atom = new BaseAtom(instruction, HashUtils.zero256());

		assertThatThrownBy(() -> this.engine.execute(List.of(atom), null, PermissionLevel.SUPER_USER))
			.isInstanceOf(RadixEngineException.class)
			.extracting(e -> ((RadixEngineException) e).getCmError().getErrorCode())
			.isEqualTo(CMErrorCode.INVALID_PARTICLE);
	}

	@Test
	public void executing_system_update_with_bad_timestamp_should_fail() {
		SystemParticle systemParticle = new SystemParticle(0, 0, 0);
		SystemParticle nextSystemParticle = new SystemParticle(0, 1, -1);
		ImmutableList<CMMicroInstruction> instructions = ImmutableList.of(
			CMMicroInstruction.virtualSpinDown(systemParticle),
			CMMicroInstruction.spinUp(nextSystemParticle),
			CMMicroInstruction.particleGroup()
		);
		CMInstruction instruction = new CMInstruction(
			instructions,
			ImmutableMap.of()
		);

		var atom = new BaseAtom(instruction, HashUtils.zero256());

		assertThatThrownBy(() -> this.engine.execute(List.of(atom), null, PermissionLevel.SUPER_USER))
			.isInstanceOf(RadixEngineException.class)
			.extracting(e -> ((RadixEngineException) e).getCmError().getErrorCode())
			.isEqualTo(CMErrorCode.INVALID_PARTICLE);
	}

	@Test
	public void executing_system_update_with_non_increasing_view_should_fail() {
		preconditionFailure(0, 0);
	}

	@Test
	public void executing_system_update_with_overly_increasing_epoch_should_fail() {
		preconditionFailure(3, 0);
	}

	@Test
	public void executing_system_update_with_epoch_starting_at_view_1_should_fail() {
		preconditionFailure(1, 1);
	}

	@Test
	@Ignore("FIXME: Possibly reinstate view ceiling at some point")
	public void executing_system_update_with_view_ceiling_should_fail() {
		// Arrange
		SystemParticle systemParticle = new SystemParticle(0, 0, 0);
		SystemParticle nextSystemParticle = new SystemParticle(0, 10, 1);
		ImmutableList<CMMicroInstruction> instructions = ImmutableList.of(
			CMMicroInstruction.virtualSpinDown(systemParticle),
			CMMicroInstruction.spinUp(nextSystemParticle),
			CMMicroInstruction.particleGroup()
		);
		CMInstruction instruction = new CMInstruction(
			instructions,
			ImmutableMap.of()
		);

		var atom = new BaseAtom(instruction, HashUtils.zero256());

		// Act
		// Assert
		assertThatThrownBy(() -> this.engine.execute(List.of(atom), null, PermissionLevel.SUPER_USER))
			.isInstanceOf(RadixEngineException.class)
			.extracting(e -> ((RadixEngineException) e).getCmError().getErrorCode())
			.isEqualTo(CMErrorCode.INVALID_PARTICLE);
	}

	private void preconditionFailure(long epoch, long view) {
		SystemParticle systemParticle = new SystemParticle(0, 0, 0);
		SystemParticle nextSystemParticle = new SystemParticle(epoch, view, 1);
		ImmutableList<CMMicroInstruction> instructions = ImmutableList.of(
			CMMicroInstruction.virtualSpinDown(systemParticle),
			CMMicroInstruction.spinUp(nextSystemParticle),
			CMMicroInstruction.particleGroup()
		);
		CMInstruction instruction = new CMInstruction(
			instructions,
			ImmutableMap.of()
		);

		var atom = new BaseAtom(instruction, HashUtils.zero256());

		assertThatThrownBy(() -> this.engine.execute(List.of(atom), null, PermissionLevel.SUPER_USER))
			.isInstanceOf(RadixEngineException.class)
			.extracting(e -> ((RadixEngineException) e).getCmError().getErrorCode())
			.isEqualTo(CMErrorCode.TRANSITION_PRECONDITION_FAILURE);
	}
}
