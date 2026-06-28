package com.jorisjonkers.personalstack.agentgateway.tmux

data class StagedInput(
    val path: String,
    val bytes: Long,
    val name: String,
)
