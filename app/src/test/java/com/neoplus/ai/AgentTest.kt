package com.neoplus.ai

import com.neoplus.ai.agent.Planner
import org.junit.Assert.assertEquals
import org.junit.Test

class AgentTest {
    private val valid = setOf("remember", "schedule_callback", "list_messages")

    @Test fun parsesPlanWithFences() {
        val raw = "```json\n{\"reply\":\"ok\",\"tasks\":[{\"id\":\"t1\",\"tool\":\"remember\",\"args\":{\"text\":\"x\"},\"depends_on\":[],\"priority\":2}," +
            "{\"id\":\"t2\",\"tool\":\"list_messages\",\"args\":{},\"depends_on\":[\"t1\"],\"priority\":9}],\"unsupported\":[\"send a message\"]}\n```"
        val plan = Planner.parsePlan(raw, valid)
        assertEquals(2, plan.tasks.size)
        assertEquals(listOf("t1"), plan.tasks[1].depends)
        assertEquals(3, plan.tasks[1].priority)
        assertEquals(listOf("send a message"), plan.unsupported)
    }

    @Test fun unknownToolBecomesUnsupported() {
        val plan = Planner.parsePlan("{\"tasks\":[{\"id\":\"t1\",\"tool\":\"hack_phone\",\"args\":{}}]}", valid)
        assertEquals(0, plan.tasks.size)
        assertEquals(1, plan.unsupported.size)
    }
}
