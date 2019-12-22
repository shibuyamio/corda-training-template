package net.corda.training.contract

import net.corda.core.contracts.*
import net.corda.core.contracts.Requirements.using
import net.corda.core.transactions.LedgerTransaction
import net.corda.finance.contracts.asset.Cash
import net.corda.finance.contracts.utils.sumCash
import net.corda.training.state.IOUState

/**
 * This is where you'll add the contract code which defines how the [IOUState] behaves. Look at the unit tests in
 * [IOUContractTests] for instructions on how to complete the [IOUContract] class.
 */
class IOUContract : Contract {
    companion object {
        @JvmStatic
        val IOU_CONTRACT_ID = "net.corda.training.contract.IOUContract"
    }

    /**
     * Add any commands required for this contract as classes within this interface.
     * It is useful to encapsulate your commands inside an interface, so you can use the [requireSingleCommand]
     * function to check for a number of commands which implement this interface.
     */
    interface Commands : CommandData {
        class Issue: TypeOnlyCommandData(), Commands
        class Settle: TypeOnlyCommandData(), Commands
    }

    /**
     * The contract code for the [IOUContract].
     * The constraints are self documenting so don't require any additional explanation.
     */
    override fun verify(tx: LedgerTransaction) {
        val command = tx.commands.requireSingleCommand<Commands>()

        when(command.value) {
            is Commands.Issue -> verifyIssueCommand(tx, command)
            is Commands.Settle -> verifySettleCommand(tx, command)
        }
    }

    private fun verifySettleCommand(tx: LedgerTransaction, command: CommandWithParties<Commands>) {
        requireThat {
            val iouStates = tx.groupStates(IOUState::class.java) { it.linearId }.single()
            "There must be one input IOU." using(iouStates.inputs.size == 1)
            // input IOU
            val inputIOU = iouStates.inputs.single()

            // Cash
            val cashStates = tx.outputsOfType<Cash.State>()
            "There must be output cash." using(cashStates.isNotEmpty())
            "There must be output cash paid to the recipient." using(
                    cashStates.none { it.owner != inputIOU.lender }
                    )

            val remainingAmount = inputIOU.amount - inputIOU.paid
            val paidCash = cashStates.sumCash().withoutIssuer()
            "The amount settled cannot be more than the amount outstanding." using(
                    remainingAmount >= paidCash
                    )
            // output IOU
            if (remainingAmount - paidCash == Amount(0, inputIOU.amount.token)) {
                "There must be no output IOU as it has been fully settled." using (iouStates.outputs.isEmpty())
            } else {
                "There must be one output IOU." using (iouStates.outputs.size == 1)
                val outputIOU = iouStates.outputs.single()
                "The borrower may not change when settling." using(outputIOU.borrower == inputIOU.borrower)
                "The amount may not change when settling." using(outputIOU.amount == inputIOU.amount)
                "The lender may not change when settling." using(outputIOU.lender == inputIOU.lender)
            }

            // Constraints on signers
            "Both lender and borrower together only must sign IOU settle transaction." using(
                    command.signers.toSet() == setOf(inputIOU.lender, inputIOU.borrower)
                    .map { it.owningKey }.toSet()
                    )
        }
    }

    private fun verifyIssueCommand(tx: LedgerTransaction, command: CommandWithParties<CommandData>) {
        requireThat {
            "No inputs should be consumed when issuing an IOU." using (tx.inputs.isEmpty())
            "Only one output state should be created when issuing an IOU." using (tx.outputs.size == 1)
            val state = tx.outputStates.single() as IOUState
            "A newly issued IOU must have a positive amount." using(state.amount > Amount(0, state.amount.token))
            "The lender and borrower cannot have the same identity." using(state.lender != state.borrower)
            "Both lender and borrower together only may sign IOU issue transaction." using(command.signers.toSet() == state.participants.map { it.owningKey }.toSet())
        }
    }
}