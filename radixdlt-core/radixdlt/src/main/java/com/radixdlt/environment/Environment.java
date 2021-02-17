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

package com.radixdlt.environment;

import com.google.inject.TypeLiteral;

/**
 * The environment where events get dispatched to. The implementing
 * environment is then responsible for getting dispatched events to their
 * processors.
 */
public interface Environment {
	<T> EventDispatcher<T> getDispatcher(Class<T> eventClass);
	<T> ScheduledEventDispatcher<T> getScheduledDispatcher(Class<T> eventClass);
	<T> ScheduledEventDispatcher<T> getScheduledDispatcher(TypeLiteral<T> typeLiteral);
	<T> RemoteEventDispatcher<T> getRemoteDispatcher(Class<T> eventClass);
}
