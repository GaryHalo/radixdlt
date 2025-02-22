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

package com.radixdlt.mempool.messages;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.google.common.hash.HashCode;
import com.radixdlt.consensus.Command;
import com.radixdlt.crypto.HashUtils;
import com.radixdlt.middleware2.network.MempoolAtomAddMessage;
import nl.jqno.equalsverifier.EqualsVerifier;
import nl.jqno.equalsverifier.Warning;
import org.junit.Before;
import org.junit.Test;

public class MempoolAtomAddMessageTest {
	private Command command;
	private MempoolAtomAddMessage message;

	@Before
	public void setUp() {
		this.command = mock(Command.class);
		this.message = new MempoolAtomAddMessage(12345, command);
	}

	@Test
	public void testGetters() {
		assertThat(message.command()).isEqualTo(command);
	}

	@Test
	public void sensibleToString() {
		assertThat(message.toString()).contains(MempoolAtomAddMessage.class.getSimpleName());
	}

	@Test
	public void equalsContract() {
		EqualsVerifier.forClass(MempoolAtomAddMessage.class)
				.withIgnoredFields("instance")
				.suppress(Warning.NONFINAL_FIELDS)
				.withPrefabValues(HashCode.class, HashUtils.random256(), HashUtils.random256())
				.verify();
	}
}
