package org.apache.archiva.web.security;
/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

import org.apache.archiva.admin.model.RepositoryAdminException;
import org.apache.archiva.admin.model.runtime.ArchivaRuntimeConfigurationAdmin;
import org.apache.archiva.redback.authentication.AuthenticationConstants;
import org.apache.archiva.redback.authentication.AuthenticationDataSource;
import org.apache.archiva.redback.authentication.AuthenticationException;
import org.apache.archiva.redback.authentication.AuthenticationResult;
import org.apache.archiva.redback.authentication.Authenticator;
import org.apache.archiva.redback.authentication.PasswordBasedAuthenticationDataSource;
import org.apache.archiva.redback.authentication.users.UserManagerAuthenticator;
import org.apache.archiva.redback.policy.AccountLockedException;
import org.apache.archiva.redback.policy.MustChangePasswordException;
import org.apache.archiva.redback.policy.PasswordEncoder;
import org.apache.archiva.redback.policy.UserSecurityPolicy;
import org.apache.archiva.redback.users.User;
import org.apache.archiva.redback.users.UserManager;
import org.apache.archiva.redback.users.UserManagerException;
import org.apache.archiva.redback.users.UserNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import javax.inject.Inject;
import javax.inject.Named;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * @author Olivier Lamy
 * @since 1.4-M4
 */
@Service( "authenticator#archiva" )
public class ArchivaUserManagerAuthenticator
    implements Authenticator
{
    private Logger log = LoggerFactory.getLogger( getClass() );

    @Inject
    private UserSecurityPolicy securityPolicy;

    @Inject
    private ApplicationContext applicationContext;

    @Inject
    private ArchivaRuntimeConfigurationAdmin archivaRuntimeConfigurationAdmin;

    private List<UserManager> userManagers;

    @PostConstruct
    protected void initialize()
        throws RepositoryAdminException
    {
        List<String> userManagerImpls =
            archivaRuntimeConfigurationAdmin.getArchivaRuntimeConfiguration().getUserManagerImpls();

        userManagers = new ArrayList<UserManager>( userManagerImpls.size() );

        for ( String beanId : userManagerImpls )
        {
            userManagers.add( applicationContext.getBean( "userManager#" + beanId, UserManager.class ) );
        }
    }


    public AuthenticationResult authenticate( AuthenticationDataSource ds )
        throws AuthenticationException, AccountLockedException, MustChangePasswordException
    {
        boolean authenticationSuccess = false;
        String username = null;
        Exception resultException = null;
        PasswordBasedAuthenticationDataSource source = (PasswordBasedAuthenticationDataSource) ds;
        Map<String, String> authnResultExceptionsMap = new HashMap<String, String>();

        for ( UserManager userManager : userManagers )
        {
            try
            {
                log.debug( "Authenticate: {} with userManager: {}", source, userManager.getId() );
                User user = userManager.findUser( source.getPrincipal() );
                username = user.getUsername();

                if ( user.isLocked() )
                {
                    //throw new AccountLockedException( "Account " + source.getPrincipal() + " is locked.", user );
                    AccountLockedException e =
                        new AccountLockedException( "Account " + source.getPrincipal() + " is locked.", user );
                    log.warn( "{}", e.getMessage() );
                    resultException = e;
                    authnResultExceptionsMap.put( AuthenticationConstants.AUTHN_LOCKED_USER_EXCEPTION, e.getMessage() );
                }

                if ( user.isPasswordChangeRequired() && source.isEnforcePasswordChange() )
                {
                    //throw new MustChangePasswordException( "Password expired.", user );
                    MustChangePasswordException e = new MustChangePasswordException( "Password expired.", user );
                    log.warn( "{}", e.getMessage() );
                    resultException = e;
                    authnResultExceptionsMap.put( AuthenticationConstants.AUTHN_MUST_CHANGE_PASSWORD_EXCEPTION,
                                                  e.getMessage() );
                }

                PasswordEncoder encoder = securityPolicy.getPasswordEncoder();
                log.debug( "PasswordEncoder: {}", encoder.getClass().getName() );

                boolean isPasswordValid = encoder.isPasswordValid( user.getEncodedPassword(), source.getPassword() );
                if ( isPasswordValid )
                {
                    log.debug( "User {} provided a valid password", source.getPrincipal() );

                    try
                    {
                        securityPolicy.extensionPasswordExpiration( user );

                        authenticationSuccess = true;

                        //REDBACK-151 do not make unnessesary updates to the user object
                        if ( user.getCountFailedLoginAttempts() > 0 )
                        {
                            user.setCountFailedLoginAttempts( 0 );
                            if ( !userManager.isReadOnly() )
                            {
                                userManager.updateUser( user );
                            }
                        }

                        return new AuthenticationResult( true, source.getPrincipal(), null );
                    }
                    catch ( MustChangePasswordException e )
                    {
                        user.setPasswordChangeRequired( true );
                        //throw e;
                        resultException = e;
                        authnResultExceptionsMap.put( AuthenticationConstants.AUTHN_MUST_CHANGE_PASSWORD_EXCEPTION,
                                                      e.getMessage() );
                    }
                }
                else
                {
                    log.warn( "Password is Invalid for user {} and userManager '{}'.", source.getPrincipal(),
                              userManager.getId() );
                    authnResultExceptionsMap.put( AuthenticationConstants.AUTHN_NO_SUCH_USER,
                                                  "Password is Invalid for user " + source.getPrincipal() + "." );

                    try
                    {

                        securityPolicy.extensionExcessiveLoginAttempts( user );

                    }
                    finally
                    {
                        if ( !userManager.isReadOnly() )
                        {
                            userManager.updateUser( user );
                        }
                    }

                    //return new AuthenticationResult( false, source.getPrincipal(), null, authnResultExceptionsMap );
                }
            }
            catch ( UserNotFoundException e )
            {
                log.warn( "Login for user {} failed. user not found.", source.getPrincipal() );
                resultException = e;
                authnResultExceptionsMap.put( AuthenticationConstants.AUTHN_NO_SUCH_USER,
                                              "Login for user " + source.getPrincipal() + " failed. user not found." );
            }
            catch ( UserManagerException e )
            {
                log.warn( "Login for user {} failed, message: {}", source.getPrincipal(), e.getMessage() );
                resultException = e;
                authnResultExceptionsMap.put( AuthenticationConstants.AUTHN_RUNTIME_EXCEPTION,
                                              "Login for user " + source.getPrincipal() + " failed, message: "
                                                  + e.getMessage() );
            }
        }
        return new AuthenticationResult( authenticationSuccess, username, resultException, authnResultExceptionsMap );
    }

    public boolean supportsDataSource( AuthenticationDataSource source )
    {
        return ( source instanceof PasswordBasedAuthenticationDataSource );
    }

    public String getId()
    {
        return "ArchivaUserManagerAuthenticator";
    }
}