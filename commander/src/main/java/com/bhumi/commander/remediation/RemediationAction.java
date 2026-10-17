
package com.bhumi.commander.remediation;

public interface RemediationAction {

    String restart(String service) throws Exception;

    String rollback(String service) throws Exception;
}
