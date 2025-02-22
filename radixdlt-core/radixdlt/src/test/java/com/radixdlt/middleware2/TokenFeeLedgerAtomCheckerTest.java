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

package com.radixdlt.middleware2;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.radixdlt.DefaultSerialization;
import com.radixdlt.atom.AtomBuilder;
import com.radixdlt.atom.Atom;
import com.radixdlt.atommodel.tokens.UnallocatedTokensParticle;
import com.radixdlt.atommodel.tokens.MutableSupplyTokenDefinitionParticle.TokenTransition;
import com.radixdlt.atommodel.tokens.TokenPermission;
import com.radixdlt.atommodel.tokens.TransferrableTokensParticle;
import com.radixdlt.atommodel.unique.UniqueParticle;
import com.radixdlt.atomos.RRIParticle;
import com.radixdlt.constraintmachine.CMInstruction;
import com.radixdlt.constraintmachine.PermissionLevel;
import com.radixdlt.crypto.ECKeyPair;
import com.radixdlt.fees.FeeTable;
import com.radixdlt.fees.PerParticleFeeEntry;
import com.radixdlt.identifiers.AID;
import com.radixdlt.identifiers.RRI;
import com.radixdlt.identifiers.RadixAddress;
import com.radixdlt.atom.ParticleGroup;
import com.radixdlt.serialization.Serialization;
import com.radixdlt.utils.UInt256;

import org.junit.Before;
import org.junit.Test;

import static org.assertj.core.api.AssertionsForClassTypes.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class TokenFeeLedgerAtomCheckerTest {

	private static final ImmutableMap<TokenTransition, TokenPermission> TOKEN_PERMISSIONS_ALL =
			ImmutableMap.of(
					TokenTransition.BURN, TokenPermission.ALL,
					TokenTransition.MINT, TokenPermission.ALL);

	private TokenFeeLedgerAtomChecker checker;
	private RRI rri;

	@Before
	public void setUp() {
		PerParticleFeeEntry feeEntry = PerParticleFeeEntry.of(UniqueParticle.class, 0, UInt256.TEN);
		FeeTable feeTable = FeeTable.from(UInt256.ZERO, ImmutableList.of(feeEntry));
		RadixAddress address = new RadixAddress((byte) 0, ECKeyPair.generateNew().getPublicKey());
		this.rri = RRI.of(address, "TESTTOKEN");
		Serialization serialization = DefaultSerialization.getInstance();
		this.checker = new TokenFeeLedgerAtomChecker(feeTable, rri, serialization);
	}

	@Test
	public void when_validating_atom_with_particles__result_has_no_error() {
		final var kp = ECKeyPair.generateNew();
		final var address = new RadixAddress((byte) 0, kp.getPublicKey());
		final var rri = RRI.of(address, "test");
		final var rriParticle = new RRIParticle(rri);
		AtomBuilder atom = Atom.newBuilder().addParticleGroup(
			ParticleGroup.builder().virtualSpinDown(rriParticle).build()
		);
		Atom ledgerAtom = atom.buildAtom();
		assertThat(checker.check(ledgerAtom, PermissionLevel.SUPER_USER).isSuccess()).isTrue();
	}

	@Test
	public void when_validating_atom_without_particles__result_has_error() {
		Atom ledgerAtom = mock(Atom.class);
		CMInstruction cmInstruction = new CMInstruction(
			ImmutableList.of(), ImmutableMap.of()
		);
		when(ledgerAtom.getAID()).thenReturn(mock(AID.class));
		when(ledgerAtom.getCMInstruction()).thenReturn(cmInstruction);

		assertThat(checker.check(ledgerAtom, PermissionLevel.SUPER_USER).getErrorMessage())
			.contains("instructions");
	}

	@Test
	public void when_validating_atom_without_fee__result_has_error() {
		RadixAddress address = new RadixAddress((byte) 0, ECKeyPair.generateNew().getPublicKey());
		UniqueParticle particle = new UniqueParticle("FOO", address, 0L);
		var atom = Atom.newBuilder().addParticleGroup(
			ParticleGroup.builder().spinUp(particle).build()
		);
		Atom ledgerAtom = atom.buildAtom();

		assertThat(checker.check(ledgerAtom, PermissionLevel.SUPER_USER).getErrorMessage())
			.contains("less than required minimum");
	}

	@Test
	public void when_validating_atom_with_fee__result_has_no_error() {
		RadixAddress address = new RadixAddress((byte) 0, ECKeyPair.generateNew().getPublicKey());
		UniqueParticle particle1 = new UniqueParticle("FOO", address, 0L);
		UnallocatedTokensParticle unallocatedParticle = new UnallocatedTokensParticle(
				UInt256.TEN, UInt256.ONE, this.rri, TOKEN_PERMISSIONS_ALL);
		TransferrableTokensParticle tokenInputParticle = new TransferrableTokensParticle(
				address, UInt256.from(20), UInt256.ONE, this.rri, TOKEN_PERMISSIONS_ALL);
		TransferrableTokensParticle tokenOutputParticle = new TransferrableTokensParticle(
				address, UInt256.TEN, UInt256.ONE, this.rri, TOKEN_PERMISSIONS_ALL);
		var atom = Atom.newBuilder()
			.addParticleGroup(ParticleGroup.builder().spinUp(particle1).build())
			.addParticleGroup(ParticleGroup.builder()
				.spinUp(unallocatedParticle)
				.spinDown(tokenInputParticle)
				.spinUp(tokenOutputParticle)
				.build()
			);
		Atom ledgerAtom = atom.buildAtom();

		assertThat(checker.check(ledgerAtom, PermissionLevel.SUPER_USER).isSuccess()).isTrue();
	}

	@Test
	public void when_validating_atom_with_fee_and_no_change__result_has_no_error() {
		RadixAddress address = new RadixAddress((byte) 0, ECKeyPair.generateNew().getPublicKey());
		UniqueParticle particle1 = new UniqueParticle("FOO", address, 0L);
		UnallocatedTokensParticle unallocatedParticle = new UnallocatedTokensParticle(
				UInt256.TEN, UInt256.ONE, this.rri, TOKEN_PERMISSIONS_ALL);
		TransferrableTokensParticle tokenInputParticle = new TransferrableTokensParticle(
				address, UInt256.TEN, UInt256.ONE, this.rri, TOKEN_PERMISSIONS_ALL);
		var atom = Atom.newBuilder()
			.addParticleGroup(ParticleGroup.builder().spinUp(particle1).build())
			.addParticleGroup(ParticleGroup.builder()
				.spinUp(unallocatedParticle)
				.spinDown(tokenInputParticle)
				.build()
			);
		Atom ledgerAtom = atom.buildAtom();

		assertThat(checker.check(ledgerAtom, PermissionLevel.SUPER_USER).isSuccess()).isTrue();
	}

	@Test
	public void when_validating_atom_with_extra_particles_in_fee_group__result_has_error() {
		RadixAddress address = new RadixAddress((byte) 0, ECKeyPair.generateNew().getPublicKey());
		UniqueParticle particle1 = new UniqueParticle("FOO", address, 0L);
		UnallocatedTokensParticle unallocatedParticle = new UnallocatedTokensParticle(
				UInt256.TEN, UInt256.ONE, this.rri, TOKEN_PERMISSIONS_ALL);
		TransferrableTokensParticle tokenInputParticle = new TransferrableTokensParticle(
				address, UInt256.TEN, UInt256.ONE, this.rri, TOKEN_PERMISSIONS_ALL);
		UniqueParticle extraFeeGroupParticle = new UniqueParticle("BAR", address, 0L);
		var atom = Atom.newBuilder()
			.addParticleGroup(ParticleGroup.builder().spinUp(particle1).build())
			.addParticleGroup(
				ParticleGroup.builder()
					.spinUp(unallocatedParticle)
					.spinDown(tokenInputParticle)
					.spinUp(extraFeeGroupParticle)
					.build()
			);
		Atom ledgerAtom = atom.buildAtom();

		assertThat(checker.check(ledgerAtom, PermissionLevel.SUPER_USER).getErrorMessage())
				.contains("less than required minimum");
	}

	@Test
	public void when_validating_atom_with_fee_and_change__result_has_no_error() {
		RadixAddress address = new RadixAddress((byte) 0, ECKeyPair.generateNew().getPublicKey());
		UniqueParticle particle1 = new UniqueParticle("FOO", address, 0L);
		UnallocatedTokensParticle particle2 = new UnallocatedTokensParticle(
				UInt256.TEN, UInt256.ONE, this.rri, TOKEN_PERMISSIONS_ALL);
		TransferrableTokensParticle particle3 = new TransferrableTokensParticle(
				address, UInt256.from(20), UInt256.ONE, this.rri, TOKEN_PERMISSIONS_ALL);
		TransferrableTokensParticle particle4 = new TransferrableTokensParticle(
				address, UInt256.TEN, UInt256.ONE, this.rri, TOKEN_PERMISSIONS_ALL);
		var atom = Atom.newBuilder()
			.addParticleGroup(ParticleGroup.builder().spinUp(particle1).build())
			.addParticleGroup(ParticleGroup.builder()
				.spinUp(particle2)
				.spinDown(particle3)
				.spinUp(particle4)
				.build()
			);
		Atom ledgerAtom = atom.buildAtom();

		assertThat(checker.check(ledgerAtom, PermissionLevel.SUPER_USER).isSuccess()).isTrue();
	}
}