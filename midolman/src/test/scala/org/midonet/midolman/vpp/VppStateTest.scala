/*
 * Copyright 2016 Midokura SARL
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.midonet.midolman.vpp

import java.util.UUID

import org.junit.runner.RunWith
import org.scalatest.{FeatureSpec, GivenWhenThen, Matchers}
import org.scalatest.junit.JUnitRunner

import org.midonet.midolman.rules.NatTarget
import org.midonet.packets.IPv4Addr

@RunWith(classOf[JUnitRunner])
class VppStateTest extends FeatureSpec with Matchers with GivenWhenThen {

    object TestableVppSate extends VppState

    feature("NAT pool is split between multiple gateways") {
        scenario("Gateways receive disjoint equal partitions") {
            Given("A NAT pool with 100 addresses")
            val pool = new NatTarget(IPv4Addr.fromInt(1),
                                     IPv4Addr.fromInt(100), 0, 0)

            And("Four gateways")
            val id1 = new UUID(0L, 0L)
            val id2 = new UUID(0L, 1L)
            val id3 = new UUID(0L, 2L)
            val id4 = new UUID(0L, 3L)
            val ids = Seq(id2, id3, id1, id4)

            Then("First gateway should get the first partition")
            TestableVppSate.splitPool(pool, id1, ids) shouldBe new NatTarget(
                IPv4Addr.fromInt(1), IPv4Addr.fromInt(25), 0, 0)

            And("Second gateway should get the second partition")
            TestableVppSate.splitPool(pool, id2, ids) shouldBe new NatTarget(
                IPv4Addr.fromInt(26), IPv4Addr.fromInt(50), 0, 0)

            And("Third gateway should get the third partition")
            TestableVppSate.splitPool(pool, id3, ids) shouldBe new NatTarget(
                IPv4Addr.fromInt(51), IPv4Addr.fromInt(75), 0, 0)

            And("Fourth gateway should get the fourth partition")
            TestableVppSate.splitPool(pool, id4, ids) shouldBe new NatTarget(
                IPv4Addr.fromInt(76), IPv4Addr.fromInt(100), 0, 0)
        }

        scenario("Gateways receive disjoint inequal partitions") {
            Given("A NAT pool with 20 addresses")
            val pool = new NatTarget(IPv4Addr.fromInt(1),
                                     IPv4Addr.fromInt(20), 0, 0)

            And("Three gateways")
            val id1 = new UUID(0L, 0L)
            val id2 = new UUID(0L, 1L)
            val id3 = new UUID(0L, 2L)
            val ids = Seq(id2, id3, id1)

            Then("First gateway should get the first partition")
            TestableVppSate.splitPool(pool, id1, ids) shouldBe new NatTarget(
                IPv4Addr.fromInt(1), IPv4Addr.fromInt(6), 0, 0)

            And("Second gateway should get the second partition")
            TestableVppSate.splitPool(pool, id2, ids) shouldBe new NatTarget(
                IPv4Addr.fromInt(7), IPv4Addr.fromInt(13), 0, 0)

            And("Third gateway should get the third partition")
            TestableVppSate.splitPool(pool, id3, ids) shouldBe new NatTarget(
                IPv4Addr.fromInt(14), IPv4Addr.fromInt(20), 0, 0)
        }

        scenario("Gateways receive same partitions even when order is different") {
            Given("A NAT pool with 100 addresses")
            val pool = new NatTarget(IPv4Addr.fromInt(1),
                                     IPv4Addr.fromInt(100), 0, 0)

            And("Three gateways")
            val id1 = new UUID(0L, 0L)
            val id2 = new UUID(0L, 1L)
            val id3 = new UUID(0L, 2L)
            val ids1 = Seq(id2, id3, id1)
            val ids2 = Seq(id1, id2, id3)
            val ids3 = Seq(id1, id3, id2)

            Then("First gateway should get the first partition")
            TestableVppSate.splitPool(pool, id1, ids2) shouldBe new NatTarget(
                IPv4Addr.fromInt(1), IPv4Addr.fromInt(33), 0, 0)

            And("Second gateway should get the second partition")
            TestableVppSate.splitPool(pool, id2, ids3) shouldBe new NatTarget(
                IPv4Addr.fromInt(34), IPv4Addr.fromInt(66), 0, 0)

            And("Third gateway should get the third partition")
            TestableVppSate.splitPool(pool, id3, ids1) shouldBe new NatTarget(
                IPv4Addr.fromInt(67), IPv4Addr.fromInt(100), 0, 0)
        }

        scenario("Some gateways may not receive addresses for small pool") {
            Given("A NAT pool with 2 addresses")
            val pool = new NatTarget(IPv4Addr.fromInt(1),
                                     IPv4Addr.fromInt(2), 0, 0)

            And("Four gateways")
            val id1 = new UUID(0L, 0L)
            val id2 = new UUID(0L, 1L)
            val id3 = new UUID(0L, 2L)
            val id4 = new UUID(0L, 3L)
            val ids = Seq(id2, id3, id1, id4)

            Then("First gateway should get no address")
            TestableVppSate.splitPool(pool, id1, ids) shouldBe new NatTarget(
                IPv4Addr.fromInt(1), IPv4Addr.fromInt(0), 0, 0)

            And("Second gateway should get the first address")
            TestableVppSate.splitPool(pool, id2, ids) shouldBe new NatTarget(
                IPv4Addr.fromInt(1), IPv4Addr.fromInt(1), 0, 0)

            And("Third gateway should get no address")
            TestableVppSate.splitPool(pool, id3, ids) shouldBe new NatTarget(
                IPv4Addr.fromInt(2), IPv4Addr.fromInt(1), 0, 0)

            And("Fourth gateway should get the second address")
            TestableVppSate.splitPool(pool, id4, ids) shouldBe new NatTarget(
                IPv4Addr.fromInt(2), IPv4Addr.fromInt(2), 0, 0)
        }
    }

}
