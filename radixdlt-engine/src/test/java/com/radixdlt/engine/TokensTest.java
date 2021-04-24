package com.radixdlt.engine;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.radixdlt.atommodel.tokens.FixedSupplyTokenDefinitionParticle;
import com.radixdlt.atommodel.tokens.TokensConstraintScrypt;
import com.radixdlt.atommodel.tokens.TransferrableTokensParticle;
import com.radixdlt.atommodel.validators.ValidatorConstraintScrypt;
import com.radixdlt.atomos.CMAtomOS;
import com.radixdlt.atomos.RRIParticle;
import com.radixdlt.constraintmachine.CMInstruction;
import com.radixdlt.constraintmachine.CMMicroInstruction;
import com.radixdlt.constraintmachine.ConstraintMachine;
import com.radixdlt.constraintmachine.Spin;
import com.radixdlt.crypto.ECKeyPair;
import com.radixdlt.crypto.HashUtils;
import com.radixdlt.identifiers.RRI;
import com.radixdlt.identifiers.RadixAddress;
import com.radixdlt.store.EngineStore;
import com.radixdlt.store.InMemoryEngineStore;
import com.radixdlt.utils.UInt256;
import org.junit.Before;
import org.junit.Test;

import java.util.List;

public class TokensTest {
	private RadixEngine<RadixEngineAtom, Void> engine;
	private EngineStore<RadixEngineAtom, Void> store;

	@Before
	public void setup() {
		CMAtomOS cmAtomOS = new CMAtomOS();
		cmAtomOS.load(new ValidatorConstraintScrypt());
		cmAtomOS.load(new TokensConstraintScrypt());
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
	public void create_new_token_with_no_errors() throws RadixEngineException {
		// Arrange
		ECKeyPair keyPair = ECKeyPair.generateNew();
		RadixAddress address = new RadixAddress((byte) 0, keyPair.getPublicKey());
		RRI rri = RRI.of(address, "TEST");
		RRIParticle rriParticle = new RRIParticle(rri);
		FixedSupplyTokenDefinitionParticle tokenDefinitionParticle = new FixedSupplyTokenDefinitionParticle(
			rri,
			"TEST",
			"description",
			UInt256.TEN,
			UInt256.ONE,
			null,
			null
		);
		TransferrableTokensParticle transferrableTokensParticle = new TransferrableTokensParticle(
			address,
			UInt256.TEN,
			UInt256.ONE,
			rri,
			ImmutableMap.of()
		);
		ImmutableList<CMMicroInstruction> instructions = ImmutableList.of(
			CMMicroInstruction.virtualSpinDown(rriParticle),
			CMMicroInstruction.spinUp(tokenDefinitionParticle),
			CMMicroInstruction.spinUp(transferrableTokensParticle),
			CMMicroInstruction.particleGroup()
		);
		CMInstruction instruction = new CMInstruction(
			instructions,
			ImmutableMap.of(keyPair.euid(), keyPair.sign(HashUtils.zero256()))
		);

		// Act
		var atom = new BaseAtom(instruction, HashUtils.zero256());
		this.engine.execute(List.of(atom));

		// Assert
		assertThat(this.store.getSpin(null, rriParticle)).isEqualTo(Spin.DOWN);
		assertThat(this.store.getSpin(null, tokenDefinitionParticle)).isEqualTo(Spin.UP);
		assertThat(this.store.getSpin(null, transferrableTokensParticle)).isEqualTo(Spin.UP);
	}
}
