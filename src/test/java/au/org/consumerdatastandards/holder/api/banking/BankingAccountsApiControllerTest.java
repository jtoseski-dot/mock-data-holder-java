package au.org.consumerdatastandards.holder.api.banking;

import au.org.consumerdatastandards.holder.api.CustomExceptionHandler;
import au.org.consumerdatastandards.holder.configuration.MethodSecurityConfig;
import au.org.consumerdatastandards.holder.configuration.ResourceServerConfig;
import au.org.consumerdatastandards.holder.model.banking.BankingAccount;
import au.org.consumerdatastandards.holder.model.banking.BankingAccountDetailV3;
import au.org.consumerdatastandards.holder.model.banking.BankingAccountV2;
import au.org.consumerdatastandards.holder.model.banking.BankingBalance;
import au.org.consumerdatastandards.holder.model.banking.BankingProductCategory;
import au.org.consumerdatastandards.holder.service.banking.BankingAccountService;
import au.org.consumerdatastandards.holder.service.banking.BankingTransactionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.aop.AopAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.time.LocalDate;
import java.util.Arrays;
import java.util.Collections;
import java.util.UUID;

import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.hasSize;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = BankingAccountsApiController.class)
@ImportAutoConfiguration(AopAutoConfiguration.class)
@ContextConfiguration(classes = {
    BankingAccountsApiController.class,
    CustomExceptionHandler.class,
    ResourceServerConfig.class,
    MethodSecurityConfig.class
})
class BankingAccountsApiControllerTest {

    private static final String BASE_PATH = "/cds-au/v1/banking/accounts";
    private static final String TOKEN_WITH_ACCOUNT_SCOPES = "token-with-account-scopes";
    private static final String TOKEN_WITHOUT_ACCOUNT_SCOPES = "token-without-account-scopes";
    private static final String AUTH_DATE = "Tue, 29 Sep 2026 10:00:00 GMT";
    private static final String ACCOUNT_ID = "account-123";

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private BankingAccountService accountService;

    @MockBean
    private BankingTransactionService transactionService;

    @MockBean
    private JwtDecoder jwtDecoder;

    @BeforeEach
    void stubTokens() {
        when(jwtDecoder.decode(TOKEN_WITH_ACCOUNT_SCOPES))
            .thenReturn(jwt(TOKEN_WITH_ACCOUNT_SCOPES, "bank:accounts.basic:read bank:accounts.detail:read"));
        when(jwtDecoder.decode(TOKEN_WITHOUT_ACCOUNT_SCOPES))
            .thenReturn(jwt(TOKEN_WITHOUT_ACCOUNT_SCOPES, "common:customer.basic:read"));
    }

    @Nested
    class ListAccounts {

        @Test
        void returnsRequiredCdrResponseHeaders() throws Exception {
            UUID interactionId = UUID.randomUUID();
            when(accountService.findBankingAccountsLike(isNull(), any(BankingAccount.class), any(Pageable.class), eq(2)))
                .thenReturn(new PageImpl<>(Collections.singletonList(account(ACCOUNT_ID)), PageRequest.of(0, 25), 1));

            mockMvc.perform(cdsGet(BASE_PATH, 2).header("x-fapi-interaction-id", interactionId))
                .andExpect(status().isOk())
                .andExpect(header().string("x-v", "2"))
                .andExpect(header().string("x-fapi-interaction-id", interactionId.toString()))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON));
        }

        @Test
        void returnsAccountsWithPaginationLinksAndMeta() throws Exception {
            when(accountService.findBankingAccountsLike(isNull(), any(BankingAccount.class), any(Pageable.class), eq(2)))
                .thenReturn(new PageImpl<>(Arrays.asList(account(ACCOUNT_ID), account("account-456")),
                    PageRequest.of(0, 25), 2));

            mockMvc.perform(cdsGet(BASE_PATH, 2))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.accounts", hasSize(2)))
                .andExpect(jsonPath("$.data.accounts[0].accountId").value(ACCOUNT_ID))
                .andExpect(jsonPath("$.data.accounts[0].displayName").value("Everyday Account"))
                .andExpect(jsonPath("$.data.accounts[0].productCategory").value("TRANS_AND_SAVINGS_ACCOUNTS"))
                .andExpect(jsonPath("$.data.accounts[1].accountId").value("account-456"))
                .andExpect(jsonPath("$.links.self").value(endsWith(BASE_PATH)))
                .andExpect(jsonPath("$.links.first").value(endsWith("?page=1&page-size=25")))
                .andExpect(jsonPath("$.links.last").value(endsWith("?page=1&page-size=25")))
                .andExpect(jsonPath("$.meta.totalRecords").value(2))
                .andExpect(jsonPath("$.meta.totalPages").value(1));
        }

        @Test
        void generatesInteractionIdWhenNotProvided() throws Exception {
            when(accountService.findBankingAccountsLike(any(), any(BankingAccount.class), any(Pageable.class), anyInt()))
                .thenReturn(Page.empty());

            MvcResult result = mockMvc.perform(cdsGet(BASE_PATH, 2))
                .andExpect(status().isOk())
                .andExpect(header().exists("x-fapi-interaction-id"))
                .andReturn();

            String interactionId = result.getResponse().getHeader("x-fapi-interaction-id");
            assertDoesNotThrow(() -> UUID.fromString(interactionId));
        }

        @Test
        void passesFiltersAndPagingToService() throws Exception {
            when(accountService.findBankingAccountsLike(any(), any(BankingAccount.class), any(Pageable.class), anyInt()))
                .thenReturn(new PageImpl<>(Collections.singletonList(account(ACCOUNT_ID)), PageRequest.of(1, 10), 11));

            mockMvc.perform(cdsGet(BASE_PATH, 2)
                    .param("is-owned", "true")
                    .param("open-status", "OPEN")
                    .param("product-category", "TRANS_AND_SAVINGS_ACCOUNTS")
                    .param("page", "2")
                    .param("page-size", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.meta.totalRecords").value(11))
                .andExpect(jsonPath("$.meta.totalPages").value(2));

            ArgumentCaptor<BankingAccount> filterCaptor = ArgumentCaptor.forClass(BankingAccount.class);
            ArgumentCaptor<Pageable> pageableCaptor = ArgumentCaptor.forClass(Pageable.class);
            verify(accountService).findBankingAccountsLike(eq(true), filterCaptor.capture(), pageableCaptor.capture(), eq(2));
            assertEquals(BankingAccount.OpenStatus.OPEN, filterCaptor.getValue().getOpenStatus());
            assertEquals(BankingProductCategory.TRANS_AND_SAVINGS_ACCOUNTS, filterCaptor.getValue().getProductCategory());
            assertEquals(1, pageableCaptor.getValue().getPageNumber());
            assertEquals(10, pageableCaptor.getValue().getPageSize());
        }

        @Test
        void openStatusAllDoesNotFilterOnStatus() throws Exception {
            when(accountService.findBankingAccountsLike(any(), any(BankingAccount.class), any(Pageable.class), anyInt()))
                .thenReturn(Page.empty());

            mockMvc.perform(cdsGet(BASE_PATH, 2).param("open-status", "ALL"))
                .andExpect(status().isOk());

            ArgumentCaptor<BankingAccount> filterCaptor = ArgumentCaptor.forClass(BankingAccount.class);
            verify(accountService).findBankingAccountsLike(isNull(), filterCaptor.capture(), any(Pageable.class), eq(2));
            assertNull(filterCaptor.getValue().getOpenStatus());
            assertNull(filterCaptor.getValue().getProductCategory());
        }

        @Test
        void negotiatesHighestSupportedVersion() throws Exception {
            when(accountService.findBankingAccountsLike(any(), any(BankingAccount.class), any(Pageable.class), anyInt()))
                .thenReturn(Page.empty());

            mockMvc.perform(cdsGet(BASE_PATH, 5).header("x-min-v", "1"))
                .andExpect(status().isOk())
                .andExpect(header().string("x-v", "2"));

            verify(accountService).findBankingAccountsLike(isNull(), any(BankingAccount.class), any(Pageable.class), eq(2));
        }

        @Test
        void rejectsUnsupportedVersion() throws Exception {
            UUID interactionId = UUID.randomUUID();

            mockMvc.perform(cdsGet(BASE_PATH, 3).header("x-fapi-interaction-id", interactionId))
                .andExpect(status().isNotAcceptable())
                .andExpect(header().string("x-fapi-interaction-id", interactionId.toString()))
                .andExpect(jsonPath("$.errors[0].code").value("urn:au-cds:error:cds-all:Header/UnsupportedVersion"));

            verify(accountService, never()).findBankingAccountsLike(any(), any(), any(), anyInt());
        }

        @Test
        void rejectsMissingVersionHeader() throws Exception {
            mockMvc.perform(get(BASE_PATH)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN_WITH_ACCOUNT_SCOPES)
                    .header("x-fapi-auth-date", AUTH_DATE))
                .andExpect(status().isBadRequest());

            verify(accountService, never()).findBankingAccountsLike(any(), any(), any(), anyInt());
        }

        @Test
        void rejectsPageSizeAboveMaximum() throws Exception {
            mockMvc.perform(cdsGet(BASE_PATH, 2).param("page-size", "1001"))
                .andExpect(status().isBadRequest())
                .andExpect(header().exists("x-fapi-interaction-id"))
                .andExpect(jsonPath("$.errors[0].code").value("urn:au-cds:error:cds-all:Field/InvalidPageSize"));
        }

        @Test
        void rejectsPageBeyondLastPage() throws Exception {
            when(accountService.findBankingAccountsLike(any(), any(BankingAccount.class), any(Pageable.class), anyInt()))
                .thenReturn(new PageImpl<>(Collections.emptyList(), PageRequest.of(4, 25), 1));

            mockMvc.perform(cdsGet(BASE_PATH, 2).param("page", "5"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errors[0].code").value("urn:au-cds:error:cds-all:Field/InvalidPage"));
        }

        @Test
        void rejectsTokenWithoutBasicAccountsScope() throws Exception {
            mockMvc.perform(get(BASE_PATH)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN_WITHOUT_ACCOUNT_SCOPES)
                    .header("x-fapi-auth-date", AUTH_DATE)
                    .header("x-v", "2"))
                .andExpect(status().isForbidden());

            verify(accountService, never()).findBankingAccountsLike(any(), any(), any(), anyInt());
        }
    }

    @Nested
    class GetAccountDetail {

        private static final String PATH = BASE_PATH + "/" + ACCOUNT_ID;

        @Test
        void returnsRequiredCdrResponseHeaders() throws Exception {
            UUID interactionId = UUID.randomUUID();
            when(accountService.getBankingAccountDetail(ACCOUNT_ID, 3)).thenReturn(accountDetail(ACCOUNT_ID));

            mockMvc.perform(cdsGet(PATH, 3).header("x-fapi-interaction-id", interactionId))
                .andExpect(status().isOk())
                .andExpect(header().string("x-v", "3"))
                .andExpect(header().string("x-fapi-interaction-id", interactionId.toString()))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON));
        }

        @Test
        void returnsAccountDetail() throws Exception {
            when(accountService.getBankingAccountDetail(ACCOUNT_ID, 3)).thenReturn(accountDetail(ACCOUNT_ID));

            mockMvc.perform(cdsGet(PATH, 3))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.accountId").value(ACCOUNT_ID))
                .andExpect(jsonPath("$.data.displayName").value("Everyday Account"))
                .andExpect(jsonPath("$.data.bsb").value("062000"))
                .andExpect(jsonPath("$.data.accountNumber").value("12345678"))
                .andExpect(jsonPath("$.links.self").value(endsWith(PATH)));
        }

        @Test
        void negotiatesHighestSupportedVersion() throws Exception {
            when(accountService.getBankingAccountDetail(ACCOUNT_ID, 3)).thenReturn(accountDetail(ACCOUNT_ID));

            mockMvc.perform(cdsGet(PATH, 5).header("x-min-v", "1"))
                .andExpect(status().isOk())
                .andExpect(header().string("x-v", "3"));

            verify(accountService).getBankingAccountDetail(ACCOUNT_ID, 3);
        }

        @Test
        void returnsNotFoundForUnknownAccount() throws Exception {
            UUID interactionId = UUID.randomUUID();
            when(accountService.getBankingAccountDetail(anyString(), anyInt())).thenReturn(null);

            mockMvc.perform(cdsGet(BASE_PATH + "/unknown-account", 3).header("x-fapi-interaction-id", interactionId))
                .andExpect(status().isNotFound())
                .andExpect(header().string("x-fapi-interaction-id", interactionId.toString()))
                .andExpect(jsonPath("$.errors[0].code")
                    .value("urn:au-cds:error:cds-banking:Authorisation/InvalidBankingAccount"))
                .andExpect(jsonPath("$.errors[0].detail").value("unknown-account"));
        }

        @Test
        void rejectsUnsupportedVersion() throws Exception {
            mockMvc.perform(cdsGet(PATH, 4))
                .andExpect(status().isNotAcceptable())
                .andExpect(header().exists("x-fapi-interaction-id"))
                .andExpect(jsonPath("$.errors[0].code").value("urn:au-cds:error:cds-all:Header/UnsupportedVersion"));

            verify(accountService, never()).getBankingAccountDetail(anyString(), anyInt());
        }

        @Test
        void rejectsMissingAuthDateHeader() throws Exception {
            mockMvc.perform(get(PATH)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN_WITH_ACCOUNT_SCOPES)
                    .header("x-v", "3"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].code").value("urn:au-cds:error:cds-all:Field/Invalid"));

            verify(accountService, never()).getBankingAccountDetail(anyString(), anyInt());
        }

        @Test
        void rejectsTokenWithoutDetailScope() throws Exception {
            mockMvc.perform(get(PATH)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN_WITHOUT_ACCOUNT_SCOPES)
                    .header("x-fapi-auth-date", AUTH_DATE)
                    .header("x-v", "3"))
                .andExpect(status().isForbidden());

            verify(accountService, never()).getBankingAccountDetail(anyString(), anyInt());
        }
    }

    @Nested
    class GetBalance {

        private static final String PATH = BASE_PATH + "/" + ACCOUNT_ID + "/balance";

        @Test
        void returnsRequiredCdrResponseHeaders() throws Exception {
            UUID interactionId = UUID.randomUUID();
            when(accountService.getBankingBalance(ACCOUNT_ID)).thenReturn(balance(ACCOUNT_ID));

            mockMvc.perform(cdsGet(PATH, 1).header("x-fapi-interaction-id", interactionId))
                .andExpect(status().isOk())
                .andExpect(header().string("x-v", "1"))
                .andExpect(header().string("x-fapi-interaction-id", interactionId.toString()))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON));
        }

        @Test
        void returnsBalance() throws Exception {
            when(accountService.getBankingBalance(ACCOUNT_ID)).thenReturn(balance(ACCOUNT_ID));

            mockMvc.perform(cdsGet(PATH, 1))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.accountId").value(ACCOUNT_ID))
                .andExpect(jsonPath("$.data.currentBalance").value("1500.25"))
                .andExpect(jsonPath("$.data.availableBalance").value("1400.00"))
                .andExpect(jsonPath("$.data.currency").value("AUD"))
                .andExpect(jsonPath("$.links.self").value(endsWith(PATH)));
        }

        @Test
        void returnsNotFoundForUnknownAccount() throws Exception {
            UUID interactionId = UUID.randomUUID();
            when(accountService.getBankingBalance(anyString())).thenReturn(null);

            mockMvc.perform(cdsGet(BASE_PATH + "/unknown-account/balance", 1)
                    .header("x-fapi-interaction-id", interactionId))
                .andExpect(status().isNotFound())
                .andExpect(header().string("x-fapi-interaction-id", interactionId.toString()))
                .andExpect(jsonPath("$.errors[0].code")
                    .value("urn:au-cds:error:cds-banking:Authorisation/InvalidBankingAccount"));
        }

        @Test
        void rejectsUnsupportedVersion() throws Exception {
            mockMvc.perform(cdsGet(PATH, 2).header("x-min-v", "2"))
                .andExpect(status().isNotAcceptable())
                .andExpect(header().exists("x-fapi-interaction-id"))
                .andExpect(jsonPath("$.errors[0].code").value("urn:au-cds:error:cds-all:Header/UnsupportedVersion"));

            verify(accountService, never()).getBankingBalance(anyString());
        }

        @Test
        void rejectsInvalidCustomerIpAddress() throws Exception {
            mockMvc.perform(cdsGet(PATH, 1)
                    .header("x-fapi-customer-ip-address", "not-an-ip")
                    .header("x-cds-client-headers", "VXNlci1BZ2VudDogdGVzdA=="))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors", hasSize(1)))
                .andExpect(jsonPath("$.errors[0].code").value("urn:au-cds:error:cds-all:Header/Invalid"));

            verify(accountService, never()).getBankingBalance(anyString());
        }

        @Test
        void requiresClientHeadersWhenCustomerIsPresent() throws Exception {
            mockMvc.perform(cdsGet(PATH, 1).header("x-fapi-customer-ip-address", "203.0.113.10"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].code").value("urn:au-cds:error:cds-all:Header/Missing"));

            verify(accountService, never()).getBankingBalance(anyString());
        }

        @Test
        void acceptsCustomerPresentHeaders() throws Exception {
            when(accountService.getBankingBalance(ACCOUNT_ID)).thenReturn(balance(ACCOUNT_ID));

            mockMvc.perform(cdsGet(PATH, 1)
                    .header("x-fapi-customer-ip-address", "203.0.113.10")
                    .header("x-cds-client-headers", "VXNlci1BZ2VudDogdGVzdA=="))
                .andExpect(status().isOk())
                .andExpect(header().string("x-v", "1"))
                .andExpect(header().exists("x-fapi-interaction-id"));
        }

        @Test
        void rejectsTokenWithoutBasicAccountsScope() throws Exception {
            mockMvc.perform(get(PATH)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN_WITHOUT_ACCOUNT_SCOPES)
                    .header("x-fapi-auth-date", AUTH_DATE)
                    .header("x-v", "1"))
                .andExpect(status().isForbidden());

            verify(accountService, never()).getBankingBalance(anyString());
        }
    }

    private static MockHttpServletRequestBuilder cdsGet(String path, int version) {
        return get(path)
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN_WITH_ACCOUNT_SCOPES)
            .header("x-fapi-auth-date", AUTH_DATE)
            .header("x-v", String.valueOf(version));
    }

    private static Jwt jwt(String tokenValue, String scope) {
        return Jwt.withTokenValue(tokenValue)
            .header("alg", "none")
            .subject("user-1")
            .claim("scope", scope)
            .build();
    }

    private static BankingAccountV2 account(String accountId) {
        BankingAccountV2 account = new BankingAccountV2();
        account.setAccountId(accountId);
        account.setDisplayName("Everyday Account");
        account.setCreationDate(LocalDate.of(2020, 1, 15));
        account.setMaskedNumber("xxxx xxxx xxxx 1234");
        account.setOpenStatus(BankingAccount.OpenStatus.OPEN);
        account.setIsOwned(true);
        account.setAccountOwnership(BankingAccount.AccountOwnershipEnum.ONE_PARTY);
        account.setProductCategory(BankingProductCategory.TRANS_AND_SAVINGS_ACCOUNTS);
        account.setProductName("Everyday Saver");
        return account;
    }

    private static BankingAccountDetailV3 accountDetail(String accountId) {
        BankingAccountDetailV3 detail = new BankingAccountDetailV3();
        detail.setAccountId(accountId);
        detail.setDisplayName("Everyday Account");
        detail.setMaskedNumber("xxxx xxxx xxxx 1234");
        detail.setOpenStatus(BankingAccount.OpenStatus.OPEN);
        detail.setIsOwned(true);
        detail.setAccountOwnership(BankingAccount.AccountOwnershipEnum.ONE_PARTY);
        detail.setProductCategory(BankingProductCategory.TRANS_AND_SAVINGS_ACCOUNTS);
        detail.setProductName("Everyday Saver");
        detail.setBsb("062000");
        detail.setAccountNumber("12345678");
        return detail;
    }

    private static BankingBalance balance(String accountId) {
        BankingBalance balance = new BankingBalance();
        balance.setAccountId(accountId);
        balance.setCurrentBalance("1500.25");
        balance.setAvailableBalance("1400.00");
        balance.setCurrency("AUD");
        return balance;
    }
}
