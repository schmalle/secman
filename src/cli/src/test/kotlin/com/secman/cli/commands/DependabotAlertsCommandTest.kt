package com.secman.cli.commands

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import picocli.CommandLine

/**
 * Unit tests for DependabotAlertsCommand option parsing.
 */
@DisplayName("DependabotAlertsCommand Parsing")
class DependabotAlertsCommandTest {

    @Test
    fun `defines org repos severity and save options`() {
        val cmd = CommandLine(DependabotAlertsCommand())
        assertThat(cmd.commandSpec.findOption("--org")).isNotNull
        assertThat(cmd.commandSpec.findOption("--repos")).isNotNull
        assertThat(cmd.commandSpec.findOption("--severity")).isNotNull
        assertThat(cmd.commandSpec.findOption("--github-token")).isNotNull
        assertThat(cmd.commandSpec.findOption("--save")).isNotNull
        assertThat(cmd.commandSpec.findOption("--dry-run")).isNotNull
    }

    @Test
    fun `parses repeatable org and comma-split repos`() {
        val command = DependabotAlertsCommand()
        CommandLine(command).parseArgs("--org", "a", "--org", "b", "--repos", "x/y,z/w")
        assertThat(command.orgs).containsExactly("a", "b")
        assertThat(command.repos).containsExactly("x/y", "z/w")
    }

    @Test
    fun `severity list starts empty so runtime default of HIGH,CRITICAL applies`() {
        val command = DependabotAlertsCommand()
        // No --severity passed → empty (picocli would otherwise append to a preset list).
        assertThat(command.severities).isEmpty()

        CommandLine(command).parseArgs("--severity", "MEDIUM")
        assertThat(command.severities).containsExactly("MEDIUM")
    }

    @Test
    fun `state defaults to open`() {
        val command = DependabotAlertsCommand()
        assertThat(command.state).isEqualTo("open")
    }
}
