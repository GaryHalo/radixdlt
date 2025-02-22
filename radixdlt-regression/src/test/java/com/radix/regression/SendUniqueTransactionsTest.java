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

package com.radix.regression;

import com.radixdlt.client.application.RadixApplicationAPI.Transaction;
import com.radixdlt.client.core.RadixEnv;
import com.radixdlt.identifiers.RRI;
import org.junit.Test;

import com.radix.test.utils.TokenUtilities;
import com.radixdlt.client.application.RadixApplicationAPI;
import com.radixdlt.client.application.identity.RadixIdentities;
import com.radixdlt.client.application.translate.ActionExecutionException;
import com.radixdlt.client.application.translate.unique.PutUniqueIdAction;

import io.reactivex.Completable;
import io.reactivex.observers.TestObserver;

/**
 * RLAU-372
 */
public class SendUniqueTransactionsTest {
	@Test
	public void given_an_account_owner_which_has_not_used_a_unique_id__when_the_client_attempts_to_use_id__then_success()
		throws Exception {

		// Given account owner which has NOT performed an action with a unique id
		RadixApplicationAPI api = RadixApplicationAPI.create(RadixEnv.getBootstrapConfig(), RadixIdentities.createNew());
		TokenUtilities.requestTokensFor(api);
		RRI unique = RRI.of(api.getAddress(), "thisisauniquestring");

		// When client attempts to use id
		TestObserver<Object> submissionObserver = TestObserver.create(Util.loggingObserver("Submission"));
		Transaction transaction = api.createTransaction();
		transaction.stage(PutUniqueIdAction.create(unique));
		Completable conflictingUniqueStatus = transaction.commitAndPush().toCompletable();
		conflictingUniqueStatus.subscribe(submissionObserver);

		// Then client should be notified of success
		submissionObserver.awaitTerminalEvent();
		submissionObserver.assertComplete();
	}

	@Test
	public void given_an_account_owner_which_has_not_used_a_unique_id__when_the_client_attempts_to_use_id_in_another_account__then_error()
		throws Exception {

		// Given account owner which has NOT performed an action with a unique id
		RadixApplicationAPI api1 = RadixApplicationAPI.create(RadixEnv.getBootstrapConfig(),  RadixIdentities.createNew());
		TokenUtilities.requestTokensFor(api1);
		RadixApplicationAPI api2 = RadixApplicationAPI.create(RadixEnv.getBootstrapConfig(), RadixIdentities.createNew());
		RRI unique = RRI.of(api2.getAddress(), "thisisauniquestring");

		// When client attempts to use id in ANOTHER account
		TestObserver<Object> submissionObserver = TestObserver.create(Util.loggingObserver("Submission"));
		Transaction transaction = api1.createTransaction();
		transaction.stage(PutUniqueIdAction.create(unique));
		Completable conflictingUniqueStatus = transaction.commitAndPush().toCompletable();
		conflictingUniqueStatus.subscribe(submissionObserver);

		// Then client should be notified of error
		submissionObserver.awaitTerminalEvent();
		submissionObserver.assertError(ActionExecutionException.class);
	}
}
