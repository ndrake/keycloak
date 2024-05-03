/*
 * Copyright 2024 Red Hat, Inc. and/or its affiliates
 * and other contributors as indicated by the @author tags.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.keycloak.testsuite.organization.admin;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import jakarta.mail.internet.MimeMessage;
import jakarta.ws.rs.core.Response;
import org.jboss.arquillian.graphene.page.Page;
import org.junit.Rule;
import org.junit.Test;
import org.keycloak.admin.client.resource.OrganizationResource;
import org.keycloak.common.Profile.Feature;
import org.keycloak.common.util.UriUtils;
import org.keycloak.cookie.CookieProvider;
import org.keycloak.cookie.CookieScope;
import org.keycloak.cookie.CookieType;
import org.keycloak.representations.idm.RealmRepresentation;
import org.keycloak.representations.idm.UserRepresentation;
import org.keycloak.testsuite.Assert;
import org.keycloak.testsuite.AssertEvents;
import org.keycloak.testsuite.admin.ApiUtil;
import org.keycloak.testsuite.arquillian.annotation.EnableFeature;
import org.keycloak.testsuite.pages.InfoPage;
import org.keycloak.testsuite.pages.RegisterPage;
import org.keycloak.testsuite.util.GreenMailRule;
import org.keycloak.testsuite.util.MailUtils;
import org.keycloak.testsuite.util.UserBuilder;

@EnableFeature(Feature.ORGANIZATION)
public class OrganizationInvitationLinkTest extends AbstractOrganizationTest {

    @Rule
    public AssertEvents events = new AssertEvents(this);

    @Rule
    public GreenMailRule greenMail = new GreenMailRule();

    @Page
    protected InfoPage infoPage;

    @Page
    protected RegisterPage registerPage;

    @Override
    public void configureTestRealm(RealmRepresentation testRealm) {
        Map<String, String> smtpConfig = testRealm.getSmtpServer();
        super.configureTestRealm(testRealm);
        testRealm.setSmtpServer(smtpConfig);
    }

    @Test
    public void testInviteExistingUser() throws IOException {
        UserRepresentation user = UserBuilder.create()
                .username("invited")
                .email("invited@myemail.com")
                .password("password")
                .enabled(true)
                .build();
        try (Response response = testRealm().users().create(user)) {
            user.setId(ApiUtil.getCreatedId(response));
        }

        OrganizationResource organization = testRealm().organizations().get(createOrganization().getId());

        organization.members().inviteExistingUser(user.getId()).close();

        MimeMessage message = greenMail.getLastReceivedMessage();
        Assert.assertNotNull(message);
        String link = MailUtils.getPasswordResetEmailLink(message);
        driver.navigate().to(link.trim());
        // not yet a member
        Assert.assertFalse(organization.members().getAll().stream().anyMatch(actual -> user.getId().equals(actual.getId())));
        // confirm the intent of membership
        infoPage.clickToContinue();
        assertThat(infoPage.getInfo(), containsString("Your account has been updated."));
        // now a member
        Assert.assertNotNull(organization.members().member(user.getId()).toRepresentation());
    }


    @Test
    public void testInviteNewUserRegistration() throws IOException {
        UserRepresentation user = UserBuilder.create()
                .username("invitedUser")
                .email("inviteduser@email")
                .enabled(true)
                .build();
        // User isn't created when we send the invite
        OrganizationResource organization = testRealm().organizations().get(createOrganization().getId());
        organization.members().inviteUser(user.getEmail()).close();

        MimeMessage message = greenMail.getLastReceivedMessage();
        Assert.assertNotNull(message);
        String link = MailUtils.getPasswordResetEmailLink(message);
        String orgToken = UriUtils.parseQueryParameters(link, false).values().stream().map(strings -> strings.get(0)).findFirst().orElse(null);
        Assert.assertNotNull(orgToken);
        driver.navigate().to(link.trim());
        Assert.assertFalse(organization.members().getAll().stream().anyMatch(actual -> user.getId().equals(actual.getId())));
        registerPage.assertCurrent();
        registerPage.register("firstName", "lastName", user.getEmail(),
                user.getUsername(), "password", "password", null, false, null);
        List<UserRepresentation> users = testRealm().users().searchByEmail(user.getEmail(), true);
        Assert.assertFalse(users.isEmpty());
        // user is a member
        Assert.assertNotNull(organization.members().member(users.get(0).getId()).toRepresentation());

        // authenticated to the account console
        Assert.assertTrue(driver.getPageSource().contains("Account Management"));
        Assert.assertNotNull(driver.manage().getCookieNamed(CookieType.IDENTITY.getName()));
    }
}