
    // --- assigning ------------------------------------------------------------------------------

    @Test
    fun choosing_multiple_owners_keeps_the_line_pending_until_confirmed() = runTest {
        val api = FakeApi()
        val c = ready(api)

        c.toggleOwner(3, "p1")
        c.toggleOwner(3, "p2")
        assertEquals(setOf("p1", "p2"), c.state.value.draft!!.lines[2].owners)
        assertFalse(c.state.value.draft!!.lines[2].confirmed)
        advanceUntilIdle()
        assertTrue(api.calls.none { it.startsWith("owners:") })

        c.accept(3)
        advanceUntilIdle()
        assertEquals("owners:i3:[p1, p2]", api.calls.last())
        assertTrue(c.state.value.draft!!.lines[2].confirmed)
    }

    @Test
    fun toggling_an_owner_adds_and_removes_them() = runTest {
        val api = FakeApi()
        val c = ready(api)

        c.toggleOwner(2, "p1")
        advanceUntilIdle()
        assertEquals(setOf("p2", "p1"), c.state.value.draft!!.lines[1].owners)

        c.toggleOwner(2, "p2")
        advanceUntilIdle()
        assertEquals(setOf("p1"), c.state.value.draft!!.lines[1].owners)
        assertFalse(c.state.value.draft!!.lines[1].confirmed)
        assertTrue(api.calls.none { it.startsWith("owners:") })

        c.accept(2)
        advanceUntilIdle()
        assertEquals("owners:i2:[p1]", api.calls.last())
    }

    @Test
    fun a_refused_confirmation_keeps_the_selection_visible_for_retry() = runTest {
        val api = FakeApi().apply { ownersResult = failure(ApiFailure.Validation(ApiError("conflict", "receipt is confirmed"))) }
        val c = ready(api)
        c.setOwners(2, setOf("p1"))
        c.accept(2)
        advanceUntilIdle()

        assertEquals(setOf("p1"), c.state.value.draft!!.lines[1].owners)
        assertFalse(c.state.value.draft!!.lines[1].confirmed)
        assertTrue(c.state.value.message!!.contains("Could not confirm that item"))
        assertTrue(c.state.value.message!!.contains("receipt is confirmed"))
    }

    @Test
    fun clearing_every_owner_leaves_the_line_unassigned() = runTest {
        val c = ready(FakeApi())

        c.setOwners(1, emptySet())
        advanceUntilIdle()

        val line = c.state.value.draft!!.lines[0]
        assertFalse(line.confirmed)
        assertEquals(0f, line.confidence)
    }

    @Test
    fun an_item_without_owners_cannot_be_confirmed() = runTest {
        val api = FakeApi()
        val c = ready(api)

        c.accept(3)
        advanceUntilIdle()

        assertFalse(c.state.value.draft!!.lines[2].confirmed)
        assertTrue(api.calls.none { it.startsWith("accept:") || it.startsWith("owners:") })
    }

    @Test
    fun an_edit_does_not_discard_owners_chosen_before_confirmation() = runTest {
        val api = FakeApi()
        val c = ready(api)
        c.setOwners(2, setOf("p1", "p2"))
        api.editResult = ApiResult.Success(edited(
            line("i1", 1, "bananas", 188, "p1"),
            line("i2", 2, "milk", 398, "p2"),
            line("i3", 3, "mystery thing", 250),
        ))

        c.editLine(2, "milk", 398)
        advanceUntilIdle()

        assertEquals(setOf("p1", "p2"), c.state.value.draft!!.lines[1].owners)
        assertFalse(c.state.value.draft!!.lines[1].confirmed)
        c.accept(2)
        advanceUntilIdle()
        assertEquals("owners:i2:[p1, p2]", api.calls.last())
    }

    @Test
    fun accepting_a_guess_confirms_that_line_on_the_server() = runTest {
        val api = FakeApi()
        val c = ready(api)

        c.accept(2)
        assertFalse(c.state.value.draft!!.lines[1].confirmed)
        advanceUntilIdle()

        assertEquals("accept:[i2]", api.calls.last())
        assertTrue(c.state.value.draft!!.lines[1].confirmed)
    }

    @Test
    fun a_refused_accept_unconfirms_the_line_again() = runTest {
        val api = FakeApi().apply { acceptResult = failure(ApiFailure.Offline) }
        val c = ready(api)

        c.accept(2)
        advanceUntilIdle()

        assertFalse(c.state.value.draft!!.lines[1].confirmed)
        assertTrue(c.state.value.message!!.startsWith("Could not confirm that item"))
    }

    @Test
    fun a_line_the_controller_does_not_know_is_ignored() = runTest {
        val api = FakeApi()
        val c = ready(api)
        val calls = api.calls.size

        c.setOwners(99, setOf("p1"))
        c.accept(99)
        c.toggleOwner(99, "p1")
        advanceUntilIdle()

        assertEquals(calls, api.calls.size)
    }

    // --- confirming -----------------------------------------------------------------------------

    @Test
    fun confirming_moves_to_confirmed() = runTest {
        val api = FakeApi()
        val c = ready(api)

        c.confirm()
        assertEquals(ReviewPhase.Confirming, c.state.value.phase)
        advanceUntilIdle()

        assertEquals(ReviewPhase.Confirmed, c.state.value.phase)
        assertEquals("confirm", api.calls.last())
    }

    @Test
    fun unconfirmed_items_come_back_marked_and_the_receipt_stays_open() = runTest {
        val api = FakeApi().apply {
            confirmResult = failure(
                ApiFailure.Validation(
                    ApiError(
                        "unconfirmed_items",
                        "1 item(s) need to be confirmed first",
                        listOf("i1")
                    )
                ),
            )
        }
        val c = ready(api)
        assertTrue(c.state.value.draft!!.lines[0].confirmed)

        c.confirm()
        advanceUntilIdle()

        assertEquals(ReviewPhase.Ready, c.state.value.phase)
        assertEquals("1 item(s) need to be confirmed first", c.state.value.message)
        assertFalse(c.state.value.draft!!.lines[0].confirmed)
    }

    @Test
    fun confirming_only_works_from_the_review_screen() = runTest {
        val api =
            FakeApi().apply { lastPoll = ApiResult.Success(receipt(ReceiptStatusDto.Processing, items = emptyList())) }
        val c = controller(api, readTimeout = 100_000)
        c.open("h1", "r1")

        c.confirm()

        assertTrue(api.calls.isEmpty())
        c.close()
    }

    @Test
    fun closing_clears_the_state_and_stops_waiting() = runTest {
        val api =
            FakeApi().apply { lastPoll = ApiResult.Success(receipt(ReceiptStatusDto.Processing, items = emptyList())) }
        val c = controller(api, readTimeout = 100_000)
        c.open("h1", "r1")
        advanceUntilIdle()
        val polls = api.pollCount

        c.close()
        advanceUntilIdle()

        assertEquals(ReviewPhase.Idle, c.state.value.phase)
        assertNotNull(c.state.value)
        assertEquals(polls, api.pollCount)
    }

    @Test
    fun opening_another_receipt_replaces_the_first() = runTest {
        val api =
            FakeApi().apply { lastPoll = ApiResult.Success(receipt(ReceiptStatusDto.Processing, items = emptyList())) }
        val c = controller(api, readTimeout = 100_000)
        c.open("h1", "r1")
        advanceUntilIdle()

        api.lastPoll = ApiResult.Success(receipt())
        c.open("h1", "r2")
        advanceUntilIdle()

        assertEquals("r2", c.state.value.receiptId)
        assertEquals(ReviewPhase.Ready, c.state.value.phase)
    }

    // --- editing lines ----------------------------------------------------------------------------

    @Test
    fun editing_a_line_saves_it_and_shows_the_servers_answer_keeping_the_other_lines_state() = runTest {
        val api = FakeApi()
        val c = ready(api)
        api.editResult = ApiResult.Success(
            edited(
                line("i1", 1, "bananas", 188, "p1"),
                line("i2", 2, "whole milk", 410, "p2"),
                line("i3", 3, "mystery thing", 250),
            ),
        )

        c.editLine(2, "  whole milk ", 410)
        advanceUntilIdle()

        assertEquals("update:i2:whole milk:410", api.calls.last())
        val lines = c.state.value.draft!!.lines
        assertEquals(listOf("bananas", "whole milk", "mystery thing"), lines.map { it.name })
        assertEquals(410, lines[1].priceCents)
        assertEquals(listOf(true, false, false), lines.map { it.confirmed })
        assertEquals(0.95f, lines[0].confidence)
        assertNull(c.state.value.message)
    }

    @Test
    fun an_edit_the_server_refuses_leaves_the_lines_alone_and_says_why() = runTest {
        val api = FakeApi().apply {
            editResult =
                failure(ApiFailure.Validation(ApiError("conflict", "receipt is confirmed and can no longer be edited")))
        }
        val c = ready(api)
        val before = c.state.value.draft

        c.editLine(1, "x", 1)
        advanceUntilIdle()

        assertEquals(before, c.state.value.draft)
        assertTrue(c.state.value.message!!.contains("can no longer be edited"))
    }

    @Test
    fun details_edit_sends_only_changed_fields_and_updates_the_draft_and_warning() = runTest {
        val api = FakeApi()
        val c = ready(api)
        api.detailsResult = ApiResult.Success(edited(
            line("i1", 1, "bananas", 188, "p1"), line("i2", 2, "great value milk", 398, "p2"), line("i3", 3, "mystery thing", 250),
            merchantName = "Target", purchasedAt = "2026-10-01T12:00:00Z", taxMinor = 50, totalMinor = 886,
            reconciled = false, subtotalGap = -20,
        ))

        c.editDetails(ReceiptDetailsEdit("Target", "2026-10-01", 50, 886))
        advanceUntilIdle()

        assertEquals("details:Target:2026-10-01T12:00:00Z:50:886", api.calls.last())
        assertEquals("Target", c.state.value.draft!!.store)
        assertEquals("Oct 1, 2026", c.state.value.draft!!.date)
        assertEquals("2026-10-01", c.state.value.draft!!.dateIso)
        assertEquals(50, c.state.value.draft!!.taxCents)
        assertEquals(886, c.state.value.draft!!.printedTotalCents)
        assertNotNull(c.state.value.warning)

        api.detailsResult = ApiResult.Success(edited(
            line("i1", 1, "bananas", 188, "p1"), line("i2", 2, "great value milk", 398, "p2"), line("i3", 3, "mystery thing", 250),
            merchantName = "Target", purchasedAt = "2026-10-01T12:00:00Z", taxMinor = 51, totalMinor = 887,
        ))
        c.editDetails(ReceiptDetailsEdit("Target", "2026-10-01", 51, 887))
        advanceUntilIdle()
        assertNull(c.state.value.warning)
    }

    @Test
    fun a_refused_details_edit_keeps_the_draft_and_shows_the_server_message() = runTest {
        val api = FakeApi().apply { detailsResult = failure(ApiFailure.Validation(ApiError("conflict", "receipt is confirmed and can no longer be edited"))) }
        val c = ready(api)
        val before = c.state.value.draft

        c.editDetails(ReceiptDetailsEdit("Target", "2026-10-01", 50, 900))
        advanceUntilIdle()

        assertEquals(before, c.state.value.draft)
        assertTrue(c.state.value.message!!.contains("receipt is confirmed"))
    }

    @Test
    fun a_new_line_starts_with_no_owner_and_needs_confirming() = runTest {
        val api = FakeApi()
        val c = ready(api)
        api.editResult = ApiResult.Success(
            edited(
                line("i1", 1, "bananas", 188, "p1"),
                line("i2", 2, "great value milk", 398, "p2"),
                line("i3", 3, "mystery thing", 250),
                line("i4", 4, "paper towels", 899),
            ),
        )

        c.addLine("paper towels", 899)
        advanceUntilIdle()

        assertEquals("add:paper towels:899", api.calls.last())
        val added = c.state.value.draft!!.lines.last()
        assertEquals("paper towels", added.name)
        assertEquals(emptySet(), added.owners)
        assertFalse(added.confirmed)
    }

    @Test
    fun deleting_a_line_removes_it_and_undo_puts_it_back() = runTest {
        val api = FakeApi()
        val c = ready(api)
        api.editResult = ApiResult.Success(
            edited(line("i1", 1, "bananas", 188, "p1"), line("i3", 2, "mystery thing", 250)),
        )

        c.deleteLine(2)
        advanceUntilIdle()

        assertEquals("delete:i2", api.calls.last())
        assertEquals(listOf("bananas", "mystery thing"), c.state.value.draft!!.lines.map { it.name })
        assertTrue(c.state.value.canUndo)

        api.editResult = ApiResult.Success(
            edited(
                line("i1", 1, "bananas", 188, "p1"),
                line("i3", 2, "mystery thing", 250),
                line("i5", 3, "great value milk", 398),
            ),
        )
        c.undoDelete()
        advanceUntilIdle()

        assertEquals("add:great value milk:398", api.calls.last())
        assertFalse(c.state.value.canUndo)
        assertEquals(3, c.state.value.draft!!.lines.size)
    }

    @Test
    fun undo_with_nothing_deleted_does_nothing() = runTest {
        val api = FakeApi()
        val c = ready(api)
        val calls = api.calls.size

        c.undoDelete()
        advanceUntilIdle()

        assertEquals(calls, api.calls.size)
    }

    @Test
    fun a_delete_the_server_refuses_cannot_be_undone() = runTest {
        val api = FakeApi().apply { editResult = failure(ApiFailure.Offline) }
        val c = ready(api)

        c.deleteLine(2)
        advanceUntilIdle()

        assertFalse(c.state.value.canUndo)
        assertEquals(3, c.state.value.draft!!.lines.size)
    }

    @Test
    fun merging_lines_sends_their_items_in_order_and_shows_the_combined_line() = runTest {
        val api = FakeApi()
        val c = ready(api)
        api.editResult =
            ApiResult.Success(edited(line("i1", 1, "bananas", 188, "p1"), line("i3", 2, "mystery thing", 250)))

        c.mergeLines(setOf(3, 2))
        advanceUntilIdle()

        assertEquals("merge:[i2, i3]", api.calls.last())
        assertEquals(2, c.state.value.draft!!.lines.size)
    }

    @Test
    fun merging_fewer_than_two_known_lines_does_nothing() = runTest {
        val api = FakeApi()
        val c = ready(api)
        val calls = api.calls.size

        c.mergeLines(setOf(2))
        c.mergeLines(setOf(2, 99))
        advanceUntilIdle()

        assertEquals(calls, api.calls.size)
    }

    @Test
    fun edits_are_ignored_before_the_draft_is_ready_and_for_unknown_lines() = runTest {
        val api =
            FakeApi().apply { lastPoll = ApiResult.Success(receipt(ReceiptStatusDto.Processing, items = emptyList())) }
        val c = controller(api, readTimeout = 100_000)
        c.open("h1", "r1")

        c.addLine("x", 1)
        advanceUntilIdle()
        assertTrue(api.calls.none { it.startsWith("add") })
        c.close()

        val ready = FakeApi()
        val d = ready(ready)
        val calls = ready.calls.size
        d.editLine(99, "x", 1)
        d.deleteLine(99)
        advanceUntilIdle()
        assertEquals(calls, ready.calls.size)
    }

    // --- the lines against the printed amounts --------------------------------------------------

    @Test
    fun a_draft_whose_lines_do_not_match_the_printed_subtotal_shows_a_warning() = runTest {
        val api = FakeApi().apply {
            lastPoll =
                ApiResult.Success(receipt().copy(subtotal = MoneyDto(1000), tax = MoneyDto(41), total = MoneyDto(1041)))
        }

        val c = ready(api)

        assertEquals(
            "The lines add up to \$8.36, which is \$1.64 less than the receipt subtotal.",
            c.state.value.warning,
        )
    }

    @Test
    fun a_draft_that_adds_up_has_no_warning_and_an_edit_can_clear_or_raise_one() = runTest {
        val api = FakeApi().apply {
            lastPoll =
                ApiResult.Success(receipt().copy(subtotal = MoneyDto(836), tax = MoneyDto(41), total = MoneyDto(877)))
        }
        val c = ready(api)
        assertNull(c.state.value.warning)

        api.editResult = ApiResult.Success(
            edited(line("i1", 1, "bananas", 188, "p1"), reconciled = false, subtotalGap = -20),
        )
        c.editLine(1, "bananas", 188)
        advanceUntilIdle()
        assertEquals(
            "The lines add up to \$1.88, which is \$0.20 more than the receipt subtotal.",
            c.state.value.warning,
        )

        api.editResult = ApiResult.Success(edited(line("i1", 1, "bananas", 188, "p1")))
        c.editLine(1, "bananas", 188)
        advanceUntilIdle()
        assertNull(c.state.value.warning)
    }

    @Test
    fun the_total_is_used_when_there_is_no_subtotal() {
        val dto = receipt().copy(subtotal = null, tax = MoneyDto(41), total = MoneyDto(900))

        val warning = reconciliationWarning(reconcile(dto))

        assertEquals("The lines add up to \$8.36, which is \$0.23 less than the receipt total.", warning)
    }

    @Test
    fun a_receipt_with_no_printed_amounts_is_never_flagged() {
        val dto = receipt().copy(subtotal = null, tax = null, total = null)

        assertNull(reconciliationWarning(reconcile(dto)))
    }

    // --- guessing again -----------------------------------------------------------------------------

    @Test
    fun guessing_again_fills_in_a_line_nobody_owned() = runTest {
        val api = FakeApi()
        val c = ready(api)
        api.lastPoll = ApiResult.Success(
            receipt(
                items = listOf(
                    item("i1", 1, "bananas", 188, guess("p1", 0.95)),
                    item("i2", 2, "great value milk", 398, guess("p2", 0.6)),
                    item("i3", 3, "mystery thing", 250, guess("p2", 0.9)),
                ),
            ),
        )
        api.predictions = ApiResult.Success(
            PredictionsDto(
                "r1",
                items = listOf(
                    ItemPredictionDto("i1", "predicted", listOf("p1"), 0.95),
                    ItemPredictionDto("i2", "predicted", listOf("p2"), 0.6, needsConfirmation = true),
                    ItemPredictionDto("i3", "predicted", listOf("p2"), 0.9),
                ),
            ),
        )

        c.guessAgain()
        assertTrue(c.state.value.guessing)
        advanceUntilIdle()

        assertTrue("rerun" in api.calls)
        assertFalse(c.state.value.guessing)
        val line = c.state.value.draft!!.lines[2]
        assertEquals(setOf("p2"), line.owners)
        assertTrue(line.confirmed)
    }

    @Test
    fun guessing_again_never_overwrites_a_confirmed_owner() = runTest {
        val api = FakeApi()
        val c = ready(api)
        c.setOwners(3, setOf("p1"))
        c.accept(3)
        advanceUntilIdle()
        api.lastPoll = ApiResult.Success(
            receipt(
                items = listOf(
                    item("i1", 1, "bananas", 188, guess("p1", 0.95)),
                    item("i2", 2, "great value milk", 398, guess("p2", 0.6)),
                    item("i3", 3, "mystery thing", 250, guess("p2", 0.9)),
                ),
            ),
        )

        c.guessAgain()
        advanceUntilIdle()

        val line = c.state.value.draft!!.lines[2]
        assertEquals(setOf("p1"), line.owners)
        assertTrue(line.confirmed)
        assertEquals(1f, line.confidence)
    }

    @Test
    fun guessing_again_keeps_a_selection_that_is_not_confirmed_yet() = runTest {
        val api = FakeApi()
        val c = ready(api)
        c.setOwners(3, setOf("p1"))
        api.lastPoll = ApiResult.Success(
            receipt(
                items = listOf(
                    item("i1", 1, "bananas", 188, guess("p1", 0.95)),
                    item("i2", 2, "great value milk", 398, guess("p2", 0.6)),
                    item("i3", 3, "mystery thing", 250, guess("p2", 0.9)),
                ),
            ),
        )

        c.guessAgain()
        advanceUntilIdle()

        val line = c.state.value.draft!!.lines[2]
        assertEquals(setOf("p1"), line.owners)
        assertFalse(line.confirmed)
    }

    @Test
    fun an_unavailable_ml_service_shows_the_servers_notice_and_changes_nothing() = runTest {
        val api = FakeApi()
        val c = ready(api)
        val before = c.state.value.draft
        val polls = api.pollCount
        api.rerunResult = ApiResult.Success(
            PredictionsDto("r1", mlAvailable = false, notice = "The guessing service is unavailable right now."),
        )

        c.guessAgain()
        advanceUntilIdle()

        assertEquals(before, c.state.value.draft)
        assertEquals("The guessing service is unavailable right now.", c.state.value.message)
        assertEquals(polls, api.pollCount)
        assertFalse(c.state.value.guessing)
    }

    @Test
    fun a_failed_guess_request_says_why_and_changes_nothing() = runTest {
        val api = FakeApi()
        val c = ready(api)
        val before = c.state.value.draft
        api.rerunResult = failure(ApiFailure.Offline)

        c.guessAgain()
        advanceUntilIdle()

        assertEquals(before, c.state.value.draft)
        assertEquals("Could not guess again. Could not reach the server.", c.state.value.message)
        assertFalse(c.state.value.guessing)
    }

    // --- mapping ----------------------------------------------------------------------------------

    @Test
    fun dates_are_shown_as_month_day_year_and_odd_input_is_left_alone() {
        assertEquals("Sep 18, 2026", formatDate("2026-09-18T12:00:00Z"))
        assertEquals("Jan 1, 2027", formatDate("2027-01-01"))
        assertEquals("sometime", formatDate("sometime"))
        assertEquals("2026-13-01", formatDate("2026-13-01"))
    }

    @Test
    fun draft_mapping_keeps_the_raw_purchase_date_and_printed_total() {
        val mapped = buildReviewLines(receipt(), listOf(person("p1", "TJ").toUiPerson(0)), person("p1", "TJ").toUiPerson(0), null).draft
        assertEquals("2026-09-18", mapped.dateIso)
        assertEquals(877, mapped.printedTotalCents)
    }

    @Test
    fun lines_without_a_line_number_fall_back_to_their_position() {
        val dto = receipt(items = listOf(item("a", 0, "x", 100), item("b", 0, "y", 200)))
        val people = listOf(person("p1", "TJ").toUiPerson(0))

        val lines = buildReviewLines(dto, people, people[0], null)

        assertEquals(listOf(1, 2), lines.draft.lines.map { it.id })
        assertEquals(mapOf(1 to "a", 2 to "b"), lines.itemIds)
    }

    @Test
    fun a_line_a_person_chose_has_full_confidence_and_a_mix_takes_the_least_sure_guess() {
        val dto = receipt(
            items = listOf(
                item("a", 1, "chosen", 100, ItemOwnerReadDto("p1")),
                item("b", 2, "two guesses", 100, guess("p1", 0.9), guess("p2", 0.7)),
                item("c", 3, "mixed", 100, ItemOwnerReadDto("p1"), guess("p2", 0.2)),
            ),
        )
        val people = listOf(person("p1", "TJ").toUiPerson(0), person("p2", "Sumedh").toUiPerson(1))

        val lines = buildReviewLines(dto, people, people[0], null).draft.lines

        assertEquals(listOf(1f, 0.7f, 1f), lines.map { it.confidence })
    }

    @Test
    fun people_get_initials_and_distinct_colours() {
        val ui = listOf(person("p1", "TJ"), person("p2", "Mary Jane")).mapIndexed { i, p -> p.toUiPerson(i) }

        assertEquals(listOf("TJ", "MJ"), ui.map { it.initials })
        assertTrue(ui[0].color != ui[1].color)
    }
}