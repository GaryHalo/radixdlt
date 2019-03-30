package com.radix.regression;

import com.radixdlt.client.application.RadixApplicationAPI;
import com.radixdlt.client.application.RadixApplicationAPI.Result;
import com.radixdlt.client.application.identity.RadixIdentities;
import com.radixdlt.client.application.translate.tokens.TokenUnitConversions;
import com.radixdlt.client.core.Bootstrap;
import com.radixdlt.client.core.network.actions.SubmitAtomResultAction;
import com.radixdlt.client.core.network.actions.SubmitAtomResultAction.SubmitAtomResultActionType;
import org.junit.Test;
import org.radix.utils.UInt256;

public class MintAndBurnTest {
	@Test
	public void given_an_account_owner_who_created_a_token__when_the_owner_mints_max_then_burns_max_then_mints_max__then_it_should_all_be_successful() throws Exception {
		RadixApplicationAPI api = RadixApplicationAPI.create(Bootstrap.LOCALHOST_SINGLENODE, RadixIdentities.createNew());
		Result result0 = api.createMultiIssuanceToken("Joshy Token", "JOSH", "Best token");
		result0.toObservable().subscribe(System.out::println);
		result0.toCompletable().blockingAwait();

		Result result1 = api.mintTokens("JOSH", TokenUnitConversions.subunitsToUnits(UInt256.MAX_VALUE));
		result1.toObservable().subscribe(System.out::println);
		result1.toCompletable().blockingAwait();

		Result result2 = api.burnTokens("JOSH", TokenUnitConversions.subunitsToUnits(UInt256.MAX_VALUE));
		result2.toObservable().subscribe(System.out::println);
		result2.toCompletable().blockingAwait();

		Result result3 = api.mintTokens("JOSH", TokenUnitConversions.subunitsToUnits(UInt256.MAX_VALUE));
		result3.toObservable().subscribe(System.out::println);
		result3.toCompletable().blockingAwait();
	}
}
