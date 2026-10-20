\## Operational limits



| Setting | Value | Source |

|---|---:|---|

| Error-rate verification threshold | 5% | `Verifier.MAX\_ERROR\_RATE` |

| Recent deployment detection window | 30 minutes | `RootCauseGuard.RECENT\_DEPLOY\_MINUTES` |

| Action cooldown per service | 5 minutes | `ActionExecutor.COOLDOWN\_MINUTES` |

| Approval expiry | 15 minutes | `LifecycleRecovery.APPROVAL\_EXPIRY\_MINUTES` |

| Pending proposal expiry | 60 minutes | `LifecycleRecovery.PENDING\_EXPIRY\_MINUTES` |

| Stale executing proposal recovery threshold | 3 minutes | `LifecycleRecovery.STALE\_EXECUTING\_MINUTES` |

| Maximum proposals per incident | 2 | `ProposalService.MAX\_PROPOSALS` |

| Verification attempts | 3 | `Verifier.DELAY\_SECONDS` |

| Verification delays | 90 seconds, then 45 seconds, then 45 seconds | `Verifier.DELAY\_SECONDS` |



Verification stops when a check passes. If all three checks fail, the verification result is marked failed. An exception during a check also marks verification as failed without scheduling another attempt.



\*\*Docker note:\*\* These values are defined in Java source rather than exposed as environment variables. Changing them requires a code change and a rebuild/redeployment.

