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
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.MediaType;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.ResultMatcher;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.matchesPattern;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
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
@ContextConfiguration(classes = {BankingAccountsApiController.class, CustomExceptionHandler.class})
@AutoConfigureMockMvc(addFilters = false)
class BankingAccountsApiControllerTest {

    private static final String BASE_PATH = "/cds-au/v1/banking/accounts";
    private static final String AUTH_DATE = "2026-01-01T00:00:00.000Z";
    private static final String UUID_REGEX =
        "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$";

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private BankingAccountService accountService;

    @MockBean
    private BankingTransactionService transactionService;

    private static MockHttpServletRequestBuilder cdrGet(String path, String xV, UUID interactionId) {
        MockHttpServletRequestBuilder builder = get(path)
            .accept(MediaType.APPLICATION_JSON)
            .header("x-v", xV)
            .header("x-fapi-auth-date", AUTH_DATE);
        if (interactionId != null) {
            builder.header("x-fapi-interaction-id", interactionId.toString());
        }
        return builder;
    }

    private static ResultMatcher[] requiredCdrHeaders(String expectedVersion, UUID expectedInteractionId) {
        return new ResultMatcher[]{
            header().string("x-v", expectedVersion),
            header().string("x-fapi-interaction-id", expectedInteractionId.toString()),
            content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON)
        };
    }

    private static BankingAccountV2 account(String accountId, String displayName) {
        BankingAccountV2 account = new BankingAccountV2();
        account.setAccountId(accountId);
        account.setDisplayName(displayName);
        account.setMaskedNumber("xxxx xxxx xxxx 1234");
        account.setOpenStatus(BankingAccount.OpenStatus.OPEN);
        account.setProductCategory(BankingProductCategory.TRANS_AND_SAVINGS_ACCOUNTS);
        account.setProductName("Everyday Account");
        return account;
    }

    @Nested
    class ListAccounts {

        @Test
        void returnsAccountsWithRequiredCdrHeaders() throws Exception {
            UUID interactionId = UUID.randomUUID();
            Page<BankingAccount> page = new PageImpl<>(
                Arrays.asList(account("acc-1", "Everyday"), account("acc-2", "Savings")),
                PageRequest.of(0, 25), 2);
            when(accountService.findBankingAccountsLike(isNull(), any(BankingAccount.class), any(Pageable.class), eq(2)))
                .thenReturn(page);

            mockMvc.perform(cdrGet(BASE_PATH, "2", interactionId))
                .andExpect(status().isOk())
                .andExpectAll(requiredCdrHeaders("2", interactionId))
                .andExpect(jsonPath("$.data.accounts", hasSize(2)))
                .andExpect(jsonPath("$.data.accounts[0].accountId").value("acc-1"))
                .andExpect(jsonPath("$.data.accounts[1].accountId").value("acc-2"))
                .andExpect(jsonPath("$.links.self").value(endsWith(BASE_PATH)))
                .andExpect(jsonPath("$.meta.totalRecords").value(2))
                .andExpect(jsonPath("$.meta.totalPages").value(1));
        }

        @Test
        void generatesInteractionIdWhenNotSupplied() throws Exception {
            when(accountService.findBankingAccountsLike(any(), any(), any(Pageable.class), anyInt()))
                .thenReturn(Page.empty());

            mockMvc.perform(cdrGet(BASE_PATH, "2", null))
                .andExpect(status().isOk())
                .andExpect(header().string("x-v", "2"))
                .andExpect(header().string("x-fapi-interaction-id", matchesPattern(UUID_REGEX)))
                .andExpect(jsonPath("$.data.accounts", hasSize(0)))
                .andExpect(jsonPath("$.meta.totalRecords").value(0));
        }

        @Test
        void negotiatesHighestSupportedVersion() throws Exception {
            UUID interactionId = UUID.randomUUID();
            when(accountService.findBankingAccountsLike(any(), any(), any(Pageable.class), eq(2)))
                .thenReturn(Page.empty());

            mockMvc.perform(cdrGet(BASE_PATH, "5", interactionId).header("x-min-v", "1"))
                .andExpect(status().isOk())
                .andExpectAll(requiredCdrHeaders("2", interactionId));
        }

        @Test
        void passesFiltersAndPagingToService() throws Exception {
            Page<BankingAccount> secondPage = new PageImpl<>(
                Collections.singletonList(account("acc-11", "Term Deposit")), PageRequest.of(1, 10), 11);
            when(accountService.findBankingAccountsLike(any(), any(), any(Pageable.class), anyInt()))
                .thenReturn(secondPage);

            mockMvc.perform(cdrGet(BASE_PATH, "2", UUID.randomUUID())
                    .param("is-owned", "true")
                    .param("open-status", "CLOSED")
                    .param("product-category", "TERM_DEPOSITS")
                    .param("page", "2")
                    .param("page-size", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.meta.totalPages").value(2))
                .andExpect(jsonPath("$.links.prev").exists())
                .andExpect(jsonPath("$.links.next").doesNotExist());

            ArgumentCaptor<BankingAccount> filter = ArgumentCaptor.forClass(BankingAccount.class);
            ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
            verify(accountService).findBankingAccountsLike(eq(true), filter.capture(), pageable.capture(), eq(2));
            assertEquals(BankingAccount.OpenStatus.CLOSED, filter.getValue().getOpenStatus());
            assertEquals(BankingProductCategory.TERM_DEPOSITS, filter.getValue().getProductCategory());
            assertEquals(1, pageable.getValue().getPageNumber());
            assertEquals(10, pageable.getValue().getPageSize());
        }

        @Test
        void openStatusAllDoesNotFilterByStatus() throws Exception {
            when(accountService.findBankingAccountsLike(any(), any(), any(Pageable.class), anyInt()))
                .thenReturn(Page.empty());

            mockMvc.perform(cdrGet(BASE_PATH, "2", UUID.randomUUID()).param("open-status", "ALL"))
                .andExpect(status().isOk());

            ArgumentCaptor<BankingAccount> filter = ArgumentCaptor.forClass(BankingAccount.class);
            verify(accountService).findBankingAccountsLike(isNull(), filter.capture(), any(Pageable.class), eq(2));
            assertNull(filter.getValue().getOpenStatus());
            assertNull(filter.getValue().getProductCategory());
        }

        @Test
        void rejectsMissingVersionHeader() throws Exception {
            mockMvc.perform(get(BASE_PATH).header("x-fapi-auth-date", AUTH_DATE))
                .andExpect(status().isBadRequest());

            verify(accountService, never()).findBankingAccountsLike(any(), any(), any(Pageable.class), anyInt());
        }

        @Test
        void rejectsUnsupportedVersion() throws Exception {
            UUID interactionId = UUID.randomUUID();

            mockMvc.perform(cdrGet(BASE_PATH, "3", interactionId).header("x-min-v", "3"))
                .andExpect(status().isNotAcceptable())
                .andExpect(header().string("x-fapi-interaction-id", interactionId.toString()))
                .andExpect(jsonPath("$.errors[0].code").value("urn:au-cds:error:cds-all:Header/UnsupportedVersion"));
        }

        @Test
        void rejectsPageSizeAboveMaximum() throws Exception {
            UUID interactionId = UUID.randomUUID();

            mockMvc.perform(cdrGet(BASE_PATH, "2", interactionId).param("page-size", "1001"))
                .andExpect(status().isBadRequest())
                .andExpect(header().string("x-fapi-interaction-id", interactionId.toString()))
                .andExpect(jsonPath("$.errors[0].code").value("urn:au-cds:error:cds-all:Field/InvalidPageSize"));
        }

        @Test
        void rejectsPageBeyondLastPage() throws Exception {
            UUID interactionId = UUID.randomUUID();
            Page<BankingAccount> page = new PageImpl<>(
                Collections.emptyList(), PageRequest.of(4, 25), 1);
            when(accountService.findBankingAccountsLike(any(), any(), any(Pageable.class), anyInt()))
                .thenReturn(page);

            mockMvc.perform(cdrGet(BASE_PATH, "2", interactionId).param("page", "5"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(header().string("x-fapi-interaction-id", interactionId.toString()))
                .andExpect(jsonPath("$.errors[0].code").value("urn:au-cds:error:cds-all:Field/InvalidPage"));
        }
    }

    @Nested
    class GetAccountDetail {

        @Test
        void returnsAccountDetailWithRequiredCdrHeaders() throws Exception {
            UUID interactionId = UUID.randomUUID();
            BankingAccountDetailV3 detail = new BankingAccountDetailV3();
            detail.setAccountId("acc-1");
            detail.setDisplayName("Everyday");
            detail.setMaskedNumber("xxxx xxxx xxxx 1234");
            detail.setProductCategory(BankingProductCategory.TRANS_AND_SAVINGS_ACCOUNTS);
            detail.setProductName("Everyday Account");
            when(accountService.getBankingAccountDetail("acc-1", 3)).thenReturn(detail);

            mockMvc.perform(cdrGet(BASE_PATH + "/acc-1", "3", interactionId))
                .andExpect(status().isOk())
                .andExpectAll(requiredCdrHeaders("3", interactionId))
                .andExpect(jsonPath("$.data.accountId").value("acc-1"))
                .andExpect(jsonPath("$.data.displayName").value("Everyday"))
                .andExpect(jsonPath("$.data.productName").value("Everyday Account"))
                .andExpect(jsonPath("$.links.self").value(endsWith(BASE_PATH + "/acc-1")));
        }

        @Test
        void requestsOlderVersionFromServiceWhenAsked() throws Exception {
            UUID interactionId = UUID.randomUUID();
            BankingAccountDetailV3 detail = new BankingAccountDetailV3();
            detail.setAccountId("acc-1");
            when(accountService.getBankingAccountDetail("acc-1", 2)).thenReturn(detail);

            mockMvc.perform(cdrGet(BASE_PATH + "/acc-1", "2", interactionId))
                .andExpect(status().isOk())
                .andExpectAll(requiredCdrHeaders("2", interactionId));

            verify(accountService).getBankingAccountDetail("acc-1", 2);
        }

        @Test
        void returnsNotFoundForUnknownAccount() throws Exception {
            UUID interactionId = UUID.randomUUID();
            when(accountService.getBankingAccountDetail(eq("missing"), anyInt())).thenReturn(null);

            mockMvc.perform(cdrGet(BASE_PATH + "/missing", "3", interactionId))
                .andExpect(status().isNotFound())
                .andExpect(header().string("x-fapi-interaction-id", interactionId.toString()))
                .andExpect(jsonPath("$.errors[0].code")
                    .value("urn:au-cds:error:cds-banking:Authorisation/InvalidBankingAccount"))
                .andExpect(jsonPath("$.errors[0].detail").value("missing"));
        }

        @Test
        void rejectsMissingVersionHeader() throws Exception {
            UUID interactionId = UUID.randomUUID();

            mockMvc.perform(get(BASE_PATH + "/acc-1")
                    .header("x-fapi-auth-date", AUTH_DATE)
                    .header("x-fapi-interaction-id", interactionId.toString()))
                .andExpect(status().isBadRequest())
                .andExpect(header().string("x-fapi-interaction-id", interactionId.toString()))
                .andExpect(jsonPath("$.errors[0].code").value("urn:au-cds:error:cds-all:Header/Missing"));
        }
    }

    @Nested
    class GetBalance {

        private BankingBalance balance(String accountId) {
            BankingBalance balance = new BankingBalance();
            balance.setAccountId(accountId);
            balance.setCurrentBalance("1500.25");
            balance.setAvailableBalance("1400.00");
            balance.setCurrency("AUD");
            return balance;
        }

        @Test
        void returnsBalanceWithRequiredCdrHeaders() throws Exception {
            UUID interactionId = UUID.randomUUID();
            when(accountService.getBankingBalance("acc-1")).thenReturn(balance("acc-1"));

            mockMvc.perform(cdrGet(BASE_PATH + "/acc-1/balance", "1", interactionId))
                .andExpect(status().isOk())
                .andExpectAll(requiredCdrHeaders("1", interactionId))
                .andExpect(jsonPath("$.data.accountId").value("acc-1"))
                .andExpect(jsonPath("$.data.currentBalance").value("1500.25"))
                .andExpect(jsonPath("$.data.availableBalance").value("1400.00"))
                .andExpect(jsonPath("$.data.currency").value("AUD"))
                .andExpect(jsonPath("$.links.self").value(endsWith(BASE_PATH + "/acc-1/balance")));
        }

        @Test
        void acceptsCustomerPresentHeaders() throws Exception {
            UUID interactionId = UUID.randomUUID();
            when(accountService.getBankingBalance("acc-1")).thenReturn(balance("acc-1"));

            mockMvc.perform(cdrGet(BASE_PATH + "/acc-1/balance", "1", interactionId)
                    .header("x-fapi-customer-ip-address", "203.0.113.10")
                    .header("x-cds-client-headers", "VXNlci1BZ2VudDogdGVzdA=="))
                .andExpect(status().isOk())
                .andExpectAll(requiredCdrHeaders("1", interactionId));
        }

        @Test
        void returnsNotFoundForUnknownAccount() throws Exception {
            UUID interactionId = UUID.randomUUID();
            when(accountService.getBankingBalance("missing")).thenReturn(null);

            mockMvc.perform(cdrGet(BASE_PATH + "/missing/balance", "1", interactionId))
                .andExpect(status().isNotFound())
                .andExpect(header().string("x-fapi-interaction-id", interactionId.toString()))
                .andExpect(jsonPath("$.errors[0].code")
                    .value("urn:au-cds:error:cds-banking:Authorisation/InvalidBankingAccount"));
        }

        @Test
        void rejectsInvalidCustomerIpAddressAndMissingClientHeaders() throws Exception {
            UUID interactionId = UUID.randomUUID();

            ResultActions result = mockMvc.perform(cdrGet(BASE_PATH + "/acc-1/balance", "1", interactionId)
                    .header("x-fapi-customer-ip-address", "not-an-ip"))
                .andExpect(status().isBadRequest())
                .andExpect(header().string("x-fapi-interaction-id", interactionId.toString()))
                .andExpect(jsonPath("$.errors", hasSize(2)));
            List<String> expectedCodes = Arrays.asList(
                "urn:au-cds:error:cds-all:Header/Invalid",
                "urn:au-cds:error:cds-all:Header/Missing");
            for (int i = 0; i < expectedCodes.size(); i++) {
                result.andExpect(jsonPath("$.errors[" + i + "].code").value(expectedCodes.get(i)));
            }
            verify(accountService, never()).getBankingBalance(any());
        }

        @Test
        void rejectsUnsupportedVersion() throws Exception {
            UUID interactionId = UUID.randomUUID();

            mockMvc.perform(cdrGet(BASE_PATH + "/acc-1/balance", "2", interactionId).header("x-min-v", "2"))
                .andExpect(status().isNotAcceptable())
                .andExpect(header().string("x-fapi-interaction-id", interactionId.toString()))
                .andExpect(jsonPath("$.errors[0].code").value("urn:au-cds:error:cds-all:Header/UnsupportedVersion"));
        }
    }
}
