package com.pacvue.deploy

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DeployServiceTest {
    @Test
    fun `parses https github remote as repo id`() {
        assertEquals(
            "lizhenqiang-pacvue/pacvue-commerce-deploy-extension",
            DeployService.parseGithubRepoId("https://github.com/lizhenqiang-pacvue/pacvue-commerce-deploy-extension.git"),
        )
    }

    @Test
    fun `parses scp-like ssh github remote as repo id`() {
        assertEquals(
            "lizhenqiang-pacvue/pacvue-commerce-deploy-extension",
            DeployService.parseGithubRepoId("git@github.com:lizhenqiang-pacvue/pacvue-commerce-deploy-extension.git"),
        )
    }

    @Test
    fun `parses ssh url github remote as repo id`() {
        assertEquals(
            "lizhenqiang-pacvue/pacvue-commerce-deploy-extension",
            DeployService.parseGithubRepoId("ssh://git@github.com/lizhenqiang-pacvue/pacvue-commerce-deploy-extension.git"),
        )
    }

    @Test
    fun `returns null for non github remotes`() {
        assertNull(DeployService.parseGithubRepoId("https://gitlab.example.com/team/repo.git"))
    }
}
