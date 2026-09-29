package au.org.consumerdatastandards.holder.api.banking;

import au.org.consumerdatastandards.holder.api.CustomExceptionHandler;
import au.org.consumerdatastandards.holder.model.banking.BankingAccount;
import au.org.consumerdatastandards.holder.model.banking.BankingAccountDetailV3;
import au.org.consumerdatastandards.holder.model.banking.BankingAccountV2;
import au.org.consumerdatastandards.holder.model.banking.BankingBalance;
import au.org.consumerdatastandards.holder.model.banking.BankingProductCategory;
import au.org.consumerdatastandards.holder.service.banking.BankingAccountService;
import au.org.consumerdatastandards.holder.service.banking.BankingTransactionService;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.UUID;

import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.matchesPattern;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = BankingAccountsApiController.class)
@ContextConfiguration(classes = {BankingAccountsApiController.class, CustomExceptionHandler.class})
@AutoConfigureMockMvc(addFilters = false)
class BankingAccountsApiControllerTest {

    private static final String BASE_PATH = "/cds-au/v1/banking/accounts";
    private static final String ACCOUNT_ID = "acc-1234";
    private static final String UNKNOWN_ACCOUNT_ID = "does-not-exist";
    private static final String FAPI_AUTH_DATE = "Tue, 29 Sep 2026 10:00:00 GMT";
    private static final String UUID_PATTERN = "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$";

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private BankingAccountService accountService;

    @MockBean
    private BankingTransactionService transactionService;

    private static MockHttpServletRequestBuilder cdrGet(String path, int xV, UUID interactionId) {
        MockHttpServletRequestBuilder builder = get(path)
                .accept(MediaType.APPLICATION_JSON)
                .header("x-v", xV)
                .header("x-fapi-auth-date", FAPI_AUTH_DATE);
        if (interactionId != null) {
            builder.header("x-fapi-interaction-id", interactionId.toString());
        }
        return builder;
    }

    private static void assertCdrResponseHeaders(ResultActions result, String expectedVersion, UUID interactionId)
            throws Exception {
        result.andExpect(header().string("x-v", expectedVersion))
                .andExpect(header().string("x-fapi-interaction-id", interactionId.toString()))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON));
    }

    private static BankingAccountV2 account(String accountId, String displayName) {
        BankingAccountV2 account = new BankingAccountV2();
        account.setAccountId(accountId);
        account.setDisplayName(displayName);
        account.setMaskedNumber("xxxx xxxx xxxx 1234");
        account.setOpenStatus(BankingAccount.OpenStatus.OPEN);
        account.setProductCategory(BankingProductCategory.TRANS_AND_SAVINGS_ACCOUNTS);
        account.setProductName("Everyday Account");
        account.setAccountOwnership(BankingAccount.AccountOwnershipEnum.ONE_PARTY);
        return account;
    }

    @Nested
    class ListAccounts {

        @Test
        void returnsAccountsWithRequiredCdrHeaders() throws Exception {
            UUID interactionId = UUID.randomUUID();
            when(accountService.findBankingAccountsLike(any(), any(BankingAccount.class), any(Pageable.class), eq(2)))
                    .thenReturn(new PageImpl<>(Arrays.asList(account("acc-1", "Everyday"), account("acc-2", "Savings"))));

            ResultActions result = mockMvc.perform(cdrGet(BASE_PATH, 2, interactionId))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.accounts", hasSize(2)))
                    .andExpect(jsonPath("$.data.accounts[0].accountId").value("acc-1"))
                    .andExpect(jsonPath("$.data.accounts[1].displayName").value("Savings"))
                    .andExpect(jsonPath("$.links.self").value("http://localhost" + BASE_PATH))
                    .andExpect(jsonPath("$.meta.totalRecords").value(2))
                    .andExpect(jsonPath("$.meta.totalPages").value(1));
            assertCdrResponseHeaders(result, "2", interactionId);
        }

        @Test
        void generatesInteractionIdWhenNotProvided() throws Exception {
            when(accountService.findBankingAccountsLike(any(), any(BankingAccount.class), any(Pageable.class), anyInt()))
                    .thenReturn(new PageImpl<>(Collections.emptyList()));

            mockMvc.perform(cdrGet(BASE_PATH, 1, null))
                    .andExpect(status().isOk())
                    .andExpect(header().string("x-v", "1"))
                    .andExpect(header().string("x-fapi-interaction-id", matchesPattern(UUID_PATTERN)))
                    .andExpect(jsonPath("$.data.accounts", hasSize(0)));
        }

        @Test
        void negotiatesHighestSupportedVersion() throws Exception {
            when(accountService.findBankingAccountsLike(any(), any(BankingAccount.class), any(Pageable.class), anyInt()))
                    .thenReturn(new PageImpl<>(Collections.emptyList()));

            mockMvc.perform(cdrGet(BASE_PATH, 5, UUID.randomUUID()).header("x-min-v", 1))
                    .andExpect(status().isOk())
                    .andExpect(header().string("x-v", "2"));
            verify(accountService).findBankingAccountsLike(any(), any(BankingAccount.class), any(Pageable.class), eq(2));
        }

        @Test
        void acceptsCustomerPresentHeaders() throws Exception {
            when(accountService.findBankingAccountsLike(any(), any(BankingAccount.class), any(Pageable.class), anyInt()))
                    .thenReturn(new PageImpl<>(Collections.emptyList()));

            mockMvc.perform(cdrGet(BASE_PATH, 2, UUID.randomUUID())
                            .header("x-fapi-customer-ip-address", "203.0.113.10")
                            .header("x-cds-client-headers",
                                    Base64.getEncoder().encodeToString("User-Agent: JUnit".getBytes())))
                    .andExpect(status().isOk());
        }

        @Test
        void rejectsInvalidCustomerIpAddress() throws Exception {
            UUID interactionId = UUID.randomUUID();

            mockMvc.perform(cdrGet(BASE_PATH, 2, interactionId)
                            .header("x-fapi-customer-ip-address", "not-an-ip")
                            .header("x-cds-client-headers",
                                    Base64.getEncoder().encodeToString("User-Agent: JUnit".getBytes())))
                    .andExpect(status().isBadRequest())
                    .andExpect(header().string("x-fapi-interaction-id", interactionId.toString()))
                    .andExpect(jsonPath("$.errors[0].code").value("urn:au-cds:error:cds-all:Header/Invalid"));
        }

        @Test
        void rejectsMissingVersionHeader() throws Exception {
            mockMvc.perform(get(BASE_PATH)
                            .header("x-fapi-auth-date", FAPI_AUTH_DATE)
                            .header("x-fapi-interaction-id", UUID.randomUUID().toString()))
                    .andExpect(status().isBadRequest());
            verify(accountService, never()).findBankingAccountsLike(any(), any(), any(), anyInt());
        }

        @Test
        void rejectsUnsupportedVersion() throws Exception {
            mockMvc.perform(cdrGet(BASE_PATH, 3, UUID.randomUUID()).header("x-min-v", 3))
                    .andExpect(status().isNotAcceptable())
                    .andExpect(jsonPath("$.errors[0].code").value("urn:au-cds:error:cds-all:Header/UnsupportedVersion"));
        }

        @Test
        void rejectsPageSizeAboveMaximum() throws Exception {
            mockMvc.perform(cdrGet(BASE_PATH, 2, UUID.randomUUID()).param("page-size", "1001"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errors[0].code").value("urn:au-cds:error:cds-all:Field/InvalidPageSize"));
            verify(accountService, never()).findBankingAccountsLike(any(), any(), any(), anyInt());
        }

        @Test
        void rejectsPageBeyondLastPage() throws Exception {
            when(accountService.findBankingAccountsLike(any(), any(BankingAccount.class), any(Pageable.class), anyInt()))
                    .thenReturn(new PageImpl<>(Collections.singletonList(account("acc-1", "Everyday"))));

            mockMvc.perform(cdrGet(BASE_PATH, 2, UUID.randomUUID()).param("page", "5"))
                    .andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.errors[0].code").value("urn:au-cds:error:cds-all:Field/InvalidPage"));
        }
    }

    @Nested
    class GetAccountDetail {

        @Test
        void returnsAccountDetailWithRequiredCdrHeaders() throws Exception {
            UUID interactionId = UUID.randomUUID();
            BankingAccountDetailV3 detail = new BankingAccountDetailV3();
            detail.setAccountId(ACCOUNT_ID);
            detail.setDisplayName("Everyday");
            detail.setMaskedNumber("xxxx xxxx xxxx 1234");
            detail.setProductName("Everyday Account");
            detail.setBsb("062000");
            detail.setAccountNumber("12345678");
            when(accountService.getBankingAccountDetail(ACCOUNT_ID, 3)).thenReturn(detail);

            ResultActions result = mockMvc.perform(cdrGet(BASE_PATH + "/" + ACCOUNT_ID, 3, interactionId))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.accountId").value(ACCOUNT_ID))
                    .andExpect(jsonPath("$.data.bsb").value("062000"))
                    .andExpect(jsonPath("$.data.accountNumber").value("12345678"))
                    .andExpect(jsonPath("$.links.self").value("http://localhost" + BASE_PATH + "/" + ACCOUNT_ID));
            assertCdrResponseHeaders(result, "3", interactionId);
        }

        @Test
        void negotiatesHighestSupportedVersion() throws Exception {
            BankingAccountDetailV3 detail = new BankingAccountDetailV3();
            detail.setAccountId(ACCOUNT_ID);
            when(accountService.getBankingAccountDetail(ACCOUNT_ID, 3)).thenReturn(detail);

            mockMvc.perform(cdrGet(BASE_PATH + "/" + ACCOUNT_ID, 10, UUID.randomUUID()).header("x-min-v", 1))
                    .andExpect(status().isOk())
                    .andExpect(header().string("x-v", "3"));
        }

        @Test
        void returnsNotFoundForUnknownAccount() throws Exception {
            UUID interactionId = UUID.randomUUID();
            when(accountService.getBankingAccountDetail(anyString(), anyInt())).thenReturn(null);

            mockMvc.perform(cdrGet(BASE_PATH + "/" + UNKNOWN_ACCOUNT_ID, 3, interactionId))
                    .andExpect(status().isNotFound())
                    .andExpect(header().string("x-fapi-interaction-id", interactionId.toString()))
                    .andExpect(jsonPath("$.errors[0].code")
                            .value("urn:au-cds:error:cds-banking:Authorisation/InvalidBankingAccount"))
                    .andExpect(jsonPath("$.errors[0].detail").value(UNKNOWN_ACCOUNT_ID));
        }

        @Test
        void rejectsMissingVersionHeader() throws Exception {
            UUID interactionId = UUID.randomUUID();

            mockMvc.perform(get(BASE_PATH + "/" + ACCOUNT_ID)
                            .header("x-fapi-auth-date", FAPI_AUTH_DATE)
                            .header("x-fapi-interaction-id", interactionId.toString()))
                    .andExpect(status().isBadRequest())
                    .andExpect(header().string("x-fapi-interaction-id", interactionId.toString()))
                    .andExpect(jsonPath("$.errors[0].code").value("urn:au-cds:error:cds-all:Header/Missing"))
                    .andExpect(jsonPath("$.errors[0].detail").value("x-v"));
        }

        @Test
        void rejectsUnsupportedVersion() throws Exception {
            mockMvc.perform(cdrGet(BASE_PATH + "/" + ACCOUNT_ID, 4, UUID.randomUUID()))
                    .andExpect(status().isNotAcceptable())
                    .andExpect(jsonPath("$.errors[0].code").value("urn:au-cds:error:cds-all:Header/UnsupportedVersion"));
            verify(accountService, never()).getBankingAccountDetail(anyString(), anyInt());
        }
    }

    @Nested
    class GetAccountBalance {

        private static final String BALANCE_PATH = BASE_PATH + "/" + ACCOUNT_ID + "/balance";

        @Test
        void returnsBalanceWithRequiredCdrHeaders() throws Exception {
            UUID interactionId = UUID.randomUUID();
            BankingBalance balance = new BankingBalance();
            balance.setAccountId(ACCOUNT_ID);
            balance.setCurrentBalance("1234.56");
            balance.setAvailableBalance("1000.00");
            balance.setCurrency("AUD");
            when(accountService.getBankingBalance(ACCOUNT_ID)).thenReturn(balance);

            ResultActions result = mockMvc.perform(cdrGet(BALANCE_PATH, 1, interactionId))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.accountId").value(ACCOUNT_ID))
                    .andExpect(jsonPath("$.data.currentBalance").value("1234.56"))
                    .andExpect(jsonPath("$.data.availableBalance").value("1000.00"))
                    .andExpect(jsonPath("$.data.currency").value("AUD"))
                    .andExpect(jsonPath("$.links.self").value("http://localhost" + BALANCE_PATH));
            assertCdrResponseHeaders(result, "1", interactionId);
        }

        @Test
        void returnsNotFoundForUnknownAccount() throws Exception {
            UUID interactionId = UUID.randomUUID();
            when(accountService.getBankingBalance(UNKNOWN_ACCOUNT_ID)).thenReturn(null);

            mockMvc.perform(cdrGet(BASE_PATH + "/" + UNKNOWN_ACCOUNT_ID + "/balance", 1, interactionId))
                    .andExpect(status().isNotFound())
                    .andExpect(header().string("x-fapi-interaction-id", interactionId.toString()))
                    .andExpect(jsonPath("$.errors[0].code")
                            .value("urn:au-cds:error:cds-banking:Authorisation/InvalidBankingAccount"));
        }

        @Test
        void rejectsUnsupportedVersion() throws Exception {
            mockMvc.perform(cdrGet(BALANCE_PATH, 2, UUID.randomUUID()))
                    .andExpect(status().isNotAcceptable())
                    .andExpect(jsonPath("$.errors[0].code").value("urn:au-cds:error:cds-all:Header/UnsupportedVersion"));
            verify(accountService, never()).getBankingBalance(anyString());
        }

        @Test
        void rejectsInvalidVersionHeader() throws Exception {
            mockMvc.perform(get(BALANCE_PATH)
                            .header(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
                            .header("x-v", "abc")
                            .header("x-fapi-auth-date", FAPI_AUTH_DATE))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errors[0].code").value("urn:au-cds:error:cds-all:Header/InvalidVersion"));
            verifyNoInteractions(accountService);
        }
    }
}
