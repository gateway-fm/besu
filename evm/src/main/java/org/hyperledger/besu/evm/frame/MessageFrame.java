/*
 * Copyright ConsenSys AG.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on
 * an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations under the License.
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package org.hyperledger.besu.evm.frame;

import static com.google.common.base.Preconditions.checkState;
import static java.util.Collections.emptySet;

import org.hyperledger.besu.datatypes.Address;
import org.hyperledger.besu.datatypes.Hash;
import org.hyperledger.besu.datatypes.VersionedHash;
import org.hyperledger.besu.datatypes.Wei;
import org.hyperledger.besu.evm.Code;
import org.hyperledger.besu.evm.code.CodeSection;
import org.hyperledger.besu.evm.internal.FixedStack.UnderflowException;
import org.hyperledger.besu.evm.internal.MemoryEntry;
import org.hyperledger.besu.evm.internal.OperandStack;
import org.hyperledger.besu.evm.internal.ReturnStack;
import org.hyperledger.besu.evm.internal.StorageEntry;
import org.hyperledger.besu.evm.log.Log;
import org.hyperledger.besu.evm.operation.Operation;
import org.hyperledger.besu.evm.worldstate.WorldUpdater;

import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;

import com.google.common.collect.HashBasedTable;
import com.google.common.collect.HashMultimap;
import com.google.common.collect.Multimap;
import com.google.common.collect.Table;
import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;
import org.apache.tuweni.bytes.MutableBytes;
import org.apache.tuweni.units.bigints.UInt256;

/**
 * A container object for all the states associated with a message.
 *
 * <p>A message corresponds to an interaction between two accounts. A Transaction spawns at least
 * one message when its processed. Messages can also spawn messages depending on the code executed
 * within a message.
 *
 * <p>Note that there is no specific Message object in the code base. Instead, message executions
 * correspond to a {@code MessageFrame} and a specific AbstractMessageProcessor. Currently, there
 * are two such AbstractMessageProcessor types:
 *
 * <p><b>Message Call ({@code MESSAGE_CALL})</b>
 *
 * <p>A message call consists of applying a set of changes to an account on behalf of another
 * account. At the minimal end of changes is a value transfer between a sender and recipient
 * account. If the recipient account contains code, that code is also executed.
 *
 * <p><b>Contract Creation ({@code CONTRACT_CREATION})</b>
 *
 * <p>A contract creation, as its name suggests, creates contract accounts. Contract initialization
 * code and a value are supplied to initialize the contract account code and balance, respectively.
 */
public class MessageFrame {

  /**
   * Message Frame State.
   *
   * <h2>Message Frame Lifecycle</h2>
   *
   * <p>The diagram below presents the message frame lifecycle:
   *
   * <pre>
   *            ------------------------------------------------------
   *            |                                                    |
   *            |                                                    v
   *            |               ---------------------     ---------------------
   *            |               |                   |     |                   |
   *            |               |    CODE_SUCCESS   | --&gt; | COMPLETED_SUCCESS |
   *            |               |                   |     |                   |
   *            |               ---------------------     ---------------------
   *            |                         ^
   *            |                         |
   *  ---------------------     ---------------------     ---------------------
   *  |                   |     |                   | --&gt; |                   |
   *  |    NOT_STARTED    | --&gt; |   CODE_EXECUTING  |     |   CODE_SUSPENDED  |
   *  |                   |     |                   | &lt;-- |                   |
   *  ---------------------     ---------------------     ---------------------
   *            |                         |
   *            |                         |
   *            |                         |                 ---------------------
   *            |                         |                 |                   |
   *            |                         |------------&gt; |      REVERTED     |
   *            |                         |                 |                   |
   *            |                         |                 ---------------------
   *            |                         |
   *            |                         v
   *            |               ---------------------     ---------------------
   *            |               |                   |     |                   |
   *            |-------------&gt; |  EXCEPTIONAL_HALT | --&gt; | COMPLETED_FAILURE |
   *                            |                   |     |                   |
   *                            ---------------------     ---------------------
   * </pre>
   *
   * <b>Message Not Started ({@link #NOT_STARTED})</b>
   *
   * <p>The message has not begun to execute yet.
   *
   * <p><b>Code Executing ({@link #CODE_EXECUTING})</b>
   *
   * <p>The message contains code and has begun executing it. The execution will continue until it
   * is halted due to (1) spawning a child message (2) encountering an exceptional halting condition
   * (2) completing successfully.
   *
   * <p><b>Code Suspended Execution ({@link #CODE_SUSPENDED})</b>
   *
   * <p>The message has spawned a child message and has suspended its execution until the child
   * message has completed and notified its parent message. The message will then continue executing
   * code ({@link #CODE_EXECUTING}) again.
   *
   * <p><b>Code Execution Completed Successfully ({@link #CODE_SUSPENDED})</b>
   *
   * <p>The code within the message has executed to completion successfully.
   *
   * <p><b>Message Exceptionally Halted ({@link #EXCEPTIONAL_HALT})</b>
   *
   * <p>The message execution has encountered an exceptional halting condition at some point during
   * its execution.
   *
   * <p><b>Message Reverted ({@link #REVERT})</b>
   *
   * <p>The message execution has requested to revert state during execution.
   *
   * <p><b>Message Execution Failed ({@link #COMPLETED_FAILED})</b>
   *
   * <p>The message execution failed to execute successfully; most likely due to encountering an
   * exceptional halting condition. At this point the message frame is finalized and the parent is
   * notified.
   *
   * <p><b>Message Execution Completed Successfully ({@link #COMPLETED_SUCCESS})</b>
   *
   * <p>The message execution completed successfully and needs to finalized and propagated to the
   * parent message that spawned it.
   */
  public enum State {

    /** Message execution has not started. */
    NOT_STARTED,

    /** Code execution within the message is in progress. */
    CODE_EXECUTING,

    /** Code execution within the message has finished successfully. */
    CODE_SUCCESS,

    /** Code execution within the message has been suspended. */
    CODE_SUSPENDED,

    /** An exceptional halting condition has occurred. */
    EXCEPTIONAL_HALT,

    /** State changes were reverted during execution. */
    REVERT,

    /** The message execution has failed to complete successfully. */
    COMPLETED_FAILED,

    /** The message execution has completed successfully. */
    COMPLETED_SUCCESS,
  }

  /** The message type the frame corresponds to. */
  public enum Type {

    /** A Contract creation message. */
    CONTRACT_CREATION,

    /** A message call message. */
    MESSAGE_CALL,
  }

  /** The constant DEFAULT_MAX_STACK_SIZE. */
  public static final int DEFAULT_MAX_STACK_SIZE = 1024;

  // Global data fields.
  private final WorldUpdater worldUpdater;

  // Metadata fields.
  private final Type type;
  private State state;

  // Machine state fields.
  private long gasRemaining;
  private final Function<Long, Hash> blockHashLookup;
  private final int maxStackSize;
  private int pc;
  private int section;
  private final Memory memory;
  private final OperandStack stack;
  private final ReturnStack returnStack;
  private Bytes output;
  private Bytes returnData;
  private final boolean isStatic;

  // Transaction substate fields.
  private final List<Log> logs;
  private long gasRefund;
  private final Set<Address> selfDestructs;
  private final Set<Address> creates;
  private final Map<Address, Wei> refunds;
  private final Set<Address> warmedUpAddresses;
  private final Multimap<Address, Bytes32> warmedUpStorage;

  // Execution Environment fields.
  private final Address recipient;
  private final Address originator;
  private final Address contract;
  private final Wei gasPrice;
  private final Bytes inputData;
  private final Address sender;
  private final Wei value;
  private final Wei apparentValue;
  private final Code code;
  private final BlockValues blockValues;
  private final int depth;
  private final MessageFrame parentMessageFrame;
  private final Deque<MessageFrame> messageFrameStack;
  private final Address miningBeneficiary;
  private Optional<Bytes> revertReason;

  private final Map<String, Object> contextVariables;
  private final Optional<List<VersionedHash>> versionedHashes;

  private final Table<Address, Bytes32, Bytes32> transientStorage = HashBasedTable.create();

  // Miscellaneous fields.
  private Optional<ExceptionalHaltReason> exceptionalHaltReason = Optional.empty();
  private Operation currentOperation;
  private final Consumer<MessageFrame> completer;
  private Optional<MemoryEntry> maybeUpdatedMemory = Optional.empty();
  private Optional<StorageEntry> maybeUpdatedStorage = Optional.empty();

  /**
   * Builder builder.
   *
   * @return the builder
   */
  public static Builder builder() {
    return new Builder();
  }

  private MessageFrame(
      final Type type,
      final Deque<MessageFrame> messageFrameStack,
      final WorldUpdater worldUpdater,
      final long initialGas,
      final Address recipient,
      final Address originator,
      final Address contract,
      final Wei gasPrice,
      final Bytes inputData,
      final Address sender,
      final Wei value,
      final Wei apparentValue,
      final Code code,
      final BlockValues blockValues,
      final int depth,
      final boolean isStatic,
      final Consumer<MessageFrame> completer,
      final Address miningBeneficiary,
      final Function<Long, Hash> blockHashLookup,
      final Map<String, Object> contextVariables,
      final Optional<Bytes> revertReason,
      final int maxStackSize,
      final Set<Address> accessListWarmAddresses,
      final Multimap<Address, Bytes32> accessListWarmStorage,
      final Optional<List<VersionedHash>> versionedHashes) {
    this.type = type;
    this.messageFrameStack = messageFrameStack;
    this.parentMessageFrame = messageFrameStack.peek();
    this.worldUpdater = worldUpdater;
    this.gasRemaining = initialGas;
    this.blockHashLookup = blockHashLookup;
    this.maxStackSize = maxStackSize;
    this.pc = 0;
    this.section = 0;
    this.memory = new Memory();
    this.stack = new OperandStack(maxStackSize);
    this.returnStack = new ReturnStack();
    returnStack.push(new ReturnStack.ReturnStackItem(0, 0, 0));
    pc = code.isValid() ? code.getCodeSection(0).getEntryPoint() : 0;
    this.output = Bytes.EMPTY;
    this.returnData = Bytes.EMPTY;
    this.logs = new ArrayList<>();
    this.gasRefund = 0L;
    this.selfDestructs = new HashSet<>();
    this.creates = new HashSet<>();
    this.refunds = new HashMap<>();
    this.recipient = recipient;
    this.originator = originator;
    this.contract = contract;
    this.gasPrice = gasPrice;
    this.inputData = inputData;
    this.sender = sender;
    this.value = value;
    this.apparentValue = apparentValue;
    this.code = code;
    this.blockValues = blockValues;
    this.depth = depth;
    this.state = State.NOT_STARTED;
    this.isStatic = isStatic;
    this.completer = completer;
    this.miningBeneficiary = miningBeneficiary;
    this.contextVariables = contextVariables;
    this.revertReason = revertReason;

    this.warmedUpAddresses = new HashSet<>(accessListWarmAddresses);
    this.warmedUpAddresses.add(sender);
    this.warmedUpAddresses.add(contract);
    this.warmedUpStorage = HashMultimap.create(accessListWarmStorage);
    this.versionedHashes = versionedHashes;

    // the warmed up addresses will always be a superset of the address keys in the warmed up
    // storage, so we can do both warm-ups in one pass
    accessListWarmAddresses.forEach(
        address ->
            Optional.ofNullable(worldUpdater.get(address))
                .ifPresent(
                    account ->
                        warmedUpStorage
                            .get(address)
                            .forEach(
                                storageKeyBytes ->
                                    account.getStorageValue(UInt256.fromBytes(storageKeyBytes)))));
  }

  /**
   * Return the program counter.
   *
   * @return the program counter
   */
  public int getPC() {
    return pc;
  }

  /**
   * Set the program counter.
   *
   * @param pc The new program counter value
   */
  public void setPC(final int pc) {
    this.pc = pc;
  }

  /**
   * Set the code section index.
   *
   * @param section the code section index
   */
  public void setSection(final int section) {
    this.section = section;
  }

  /**
   * Return the current code section. Always zero for legacy code.
   *
   * @return the current code section
   */
  public int getSection() {
    return section;
  }

  /**
   * Call function and return exceptional halt reason.
   *
   * @param calledSection the called section
   * @return the exceptional halt reason
   */
  public ExceptionalHaltReason callFunction(final int calledSection) {
    CodeSection info = code.getCodeSection(calledSection);
    if (info == null) {
      return ExceptionalHaltReason.CODE_SECTION_MISSING;
    } else if (stack.size() + info.getMaxStackHeight() > maxStackSize) {
      return ExceptionalHaltReason.TOO_MANY_STACK_ITEMS;
    } else if (stack.size() < info.getInputs()) {
      return ExceptionalHaltReason.TOO_FEW_INPUTS_FOR_CODE_SECTION;
    } else {
      returnStack.push(
          new ReturnStack.ReturnStackItem(section, pc + 2, stack.size() - info.getInputs()));
      pc = info.getEntryPoint() - 1; // will be +1ed at end of operations loop
      this.section = calledSection;
      return null;
    }
  }

  /**
   * Jump function exceptional halt reason.
   *
   * @param section the section
   * @return the exceptional halt reason
   */
  public ExceptionalHaltReason jumpFunction(final int section) {
    CodeSection info = code.getCodeSection(section);
    if (info == null) {
      return ExceptionalHaltReason.CODE_SECTION_MISSING;
    } else if (stackSize() != peekReturnStack().getStackHeight() + info.getInputs()) {
      return ExceptionalHaltReason.JUMPF_STACK_MISMATCH;
    } else {
      pc = -1; // will be +1ed at end of operations loop
      this.section = section;
      return null;
    }
  }

  /**
   * Return function exceptional halt reason.
   *
   * @return the exceptional halt reason
   */
  public ExceptionalHaltReason returnFunction() {
    CodeSection thisInfo = code.getCodeSection(this.section);
    var returnInfo = returnStack.pop();
    if ((returnInfo.getStackHeight() + thisInfo.getOutputs()) != stack.size()) {
      return ExceptionalHaltReason.INCORRECT_CODE_SECTION_RETURN_OUTPUTS;
    } else if (returnStack.isEmpty()) {
      setState(MessageFrame.State.CODE_SUCCESS);
      setOutputData(Bytes.EMPTY);
      return null;
    } else {
      this.pc = returnInfo.getPC();
      this.section = returnInfo.getCodeSectionIndex();
      return null;
    }
  }

  /** Deducts the remaining gas. */
  public void clearGasRemaining() {
    this.gasRemaining = 0L;
  }

  /**
   * Decrement the amount of remaining gas.
   *
   * @param amount The amount of gas to deduct
   * @return the amount of gas available, after deductions.
   */
  public long decrementRemainingGas(final long amount) {
    return this.gasRemaining -= amount;
  }

  /**
   * Return the amount of remaining gas.
   *
   * @return the amount of remaining gas
   */
  public long getRemainingGas() {
    return gasRemaining;
  }

  /**
   * Increment the amount of remaining gas.
   *
   * @param amount The amount of gas to increment
   */
  public void incrementRemainingGas(final long amount) {
    this.gasRemaining += amount;
  }

  /**
   * Set the amount of remaining gas.
   *
   * @param amount The amount of remaining gas
   */
  public void setGasRemaining(final long amount) {
    this.gasRemaining = amount;
  }

  /**
   * Return the output data.
   *
   * @return the output data
   */
  public Bytes getOutputData() {
    return output;
  }

  /**
   * Set the output data.
   *
   * @param output The output data
   */
  public void setOutputData(final Bytes output) {
    this.output = output;
  }

  /** Clears the output data buffer. */
  public void clearOutputData() {
    setOutputData(Bytes.EMPTY);
  }

  /**
   * Return the return data.
   *
   * @return the return data
   */
  public Bytes getReturnData() {
    return returnData;
  }

  /**
   * Set the return data.
   *
   * @param returnData The return data
   */
  public void setReturnData(final Bytes returnData) {
    this.returnData = returnData;
  }

  /** Clear the return data buffer. */
  public void clearReturnData() {
    setReturnData(Bytes.EMPTY);
  }

  /**
   * Returns the item at the specified offset in the stack.
   *
   * @param offset The item's position relative to the top of the stack
   * @return The item at the specified offset in the stack
   * @throws UnderflowException if the offset is out of range
   */
  public Bytes getStackItem(final int offset) {
    return stack.get(offset);
  }

  /**
   * Removes the item at the top of the stack.
   *
   * @return the item at the top of the stack
   * @throws UnderflowException if the stack is empty
   */
  public Bytes popStackItem() {
    return stack.pop();
  }

  /**
   * Removes the corresponding number of items from the top of the stack.
   *
   * @param n The number of items to pop off the stack
   */
  public void popStackItems(final int n) {
    stack.bulkPop(n);
  }

  /**
   * Pushes the corresponding item onto the top of the stack
   *
   * @param value The value to push onto the stack.
   */
  public void pushStackItem(final Bytes value) {
    stack.push(value);
  }

  /**
   * Sets the stack item at the specified offset from the top of the stack to the value
   *
   * @param offset The item's position relative to the top of the stack
   * @param value The value to set the stack item to
   * @throws IllegalStateException if the stack is too small
   */
  public void setStackItem(final int offset, final Bytes value) {
    stack.set(offset, value);
  }

  /**
   * Return the current stack size.
   *
   * @return The current stack size
   */
  public int stackSize() {
    return stack.size();
  }

  /**
   * Return the current return stack size.
   *
   * @return The current return stack size
   */
  public int returnStackSize() {
    return returnStack.size();
  }

  /**
   * The top item of the return stack
   *
   * @return The top item of the return stack, or null if the stack is empty
   */
  public ReturnStack.ReturnStackItem peekReturnStack() {
    return returnStack.peek();
  }

  /**
   * Pushes a new return stack item onto the return stack
   *
   * @param returnStackItem item to be pushed
   */
  public void pushReturnStackItem(final ReturnStack.ReturnStackItem returnStackItem) {
    returnStack.push(returnStackItem);
  }

  /**
   * Returns whether the message frame is static or not.
   *
   * @return {@code} true if the frame is static; otherwise {@code false}
   */
  public boolean isStatic() {
    return isStatic;
  }

  /**
   * Returns the memory size for specified memory access.
   *
   * @param offset The offset in memory
   * @param length The length of the memory access
   * @return the memory size for specified memory access
   */
  public long calculateMemoryExpansion(final long offset, final long length) {
    return memory.calculateNewActiveWords(offset, length);
  }

  /**
   * Expands memory to accommodate the specified memory access.
   *
   * @param offset The offset in memory
   * @param length The length of the memory access
   */
  public void expandMemory(final long offset, final long length) {
    memory.ensureCapacityForBytes(offset, length);
  }

  /**
   * Returns the number of bytes in memory.
   *
   * @return the number of bytes in memory
   */
  public long memoryByteSize() {
    return memory.getActiveBytes();
  }

  /**
   * Returns the number of words in memory.
   *
   * @return the number of words in memory
   */
  public int memoryWordSize() {
    return memory.getActiveWords();
  }

  /**
   * Returns the revertReason as string
   *
   * @return the revertReason string
   */
  public Optional<Bytes> getRevertReason() {
    return revertReason;
  }

  /**
   * Sets revert reason.
   *
   * @param revertReason the revert reason
   */
  public void setRevertReason(final Bytes revertReason) {
    this.revertReason = Optional.ofNullable(revertReason);
  }

  /**
   * Read bytes in memory as mutable. Contents should not be considered stable outside the scope of
   * the current operation.
   *
   * @param offset The offset in memory
   * @param length The length of the bytes to read
   * @return The bytes in the specified range
   */
  public MutableBytes readMutableMemory(final long offset, final long length) {
    return readMutableMemory(offset, length, false);
  }

  /**
   * Read bytes in memory .
   *
   * @param offset The offset in memory
   * @param length The length of the bytes to read
   * @return The bytes in the specified range
   */
  public Bytes readMemory(final long offset, final long length) {
    return readMutableMemory(offset, length, false).copy();
  }

  /**
   * Read bytes in memory. Contents should not be considered stable outside the scope of the current
   * operation.
   *
   * @param offset The offset in memory
   * @param length The length of the bytes to read
   * @param explicitMemoryRead true if triggered by a memory opcode, false otherwise
   * @return The bytes in the specified range
   */
  public MutableBytes readMutableMemory(
      final long offset, final long length, final boolean explicitMemoryRead) {
    final MutableBytes memBytes = memory.getMutableBytes(offset, length);
    if (explicitMemoryRead) {
      setUpdatedMemory(offset, memBytes);
    }
    return memBytes;
  }

  /**
   * Write byte to memory
   *
   * @param offset The offset in memory
   * @param value The value to set in memory
   * @param explicitMemoryUpdate true if triggered by a memory opcode, false otherwise
   */
  public void writeMemory(final long offset, final byte value, final boolean explicitMemoryUpdate) {
    memory.setByte(offset, value);
    if (explicitMemoryUpdate) {
      setUpdatedMemory(offset, Bytes.of(value));
    }
  }

  /**
   * Write bytes to memory
   *
   * @param offset The offset in memory
   * @param length The length of the bytes to write
   * @param value The value to write
   */
  public void writeMemory(final long offset, final long length, final Bytes value) {
    writeMemory(offset, length, value, false);
  }

  /**
   * Write bytes to memory
   *
   * @param offset The offset in memory
   * @param length The length of the bytes to write
   * @param value The value to write
   * @param explicitMemoryUpdate true if triggered by a memory opcode, false otherwise
   */
  public void writeMemory(
      final long offset, final long length, final Bytes value, final boolean explicitMemoryUpdate) {
    memory.setBytes(offset, length, value);
    if (explicitMemoryUpdate) {
      setUpdatedMemory(offset, 0, length, value);
    }
  }

  /**
   * Copy the bytes from the value param into memory at the specified offset. In cases where the
   * value does not have numBytes bytes the appropriate amount of zero bytes will be added before
   * writing the value bytes.
   *
   * @param offset The offset in memory
   * @param length The length of the bytes to write
   * @param value The value to write
   * @param explicitMemoryUpdate true if triggered by a memory opcode, false otherwise
   */
  public void writeMemoryRightAligned(
      final long offset, final long length, final Bytes value, final boolean explicitMemoryUpdate) {
    memory.setBytesRightAligned(offset, length, value);
    if (explicitMemoryUpdate) {
      setUpdatedMemoryRightAligned(offset, length, value);
    }
  }

  /**
   * Write bytes to memory
   *
   * @param offset The offset in memory to start writing
   * @param sourceOffset The offset in the source value to start writing
   * @param length The length of the bytes to write
   * @param value The value to write
   */
  public void writeMemory(
      final long offset, final long sourceOffset, final long length, final Bytes value) {
    writeMemory(offset, sourceOffset, length, value, false);
  }

  /**
   * Write bytes to memory
   *
   * @param offset The offset in memory to start writing
   * @param sourceOffset The offset in the source value to start writing
   * @param length The length of the bytes to write
   * @param value The value to write
   * @param explicitMemoryUpdate true if triggered by a memory opcode, false otherwise
   */
  public void writeMemory(
      final long offset,
      final long sourceOffset,
      final long length,
      final Bytes value,
      final boolean explicitMemoryUpdate) {
    memory.setBytes(offset, sourceOffset, length, value);
    if (explicitMemoryUpdate && length > 0) {
      setUpdatedMemory(offset, sourceOffset, length, value);
    }
  }

  /**
   * Copies bytes within memory.
   *
   * <p>Copying behaves as if the values are copied to an intermediate buffer before writing.
   *
   * @param dst The destination address
   * @param src The source address
   * @param length the number of bytes to copy
   * @param explicitMemoryUpdate true if triggered by a memory opcode, false otherwise
   */
  public void copyMemory(
      final long dst, final long src, final long length, final boolean explicitMemoryUpdate) {
    if (length > 0) {
      memory.copy(dst, src, length);
      if (explicitMemoryUpdate) {
        setUpdatedMemory(dst, memory.getBytes(dst, length));
      }
    }
  }

  private void setUpdatedMemory(
      final long offset, final long sourceOffset, final long length, final Bytes value) {
    final long endIndex = sourceOffset + length;
    if (sourceOffset >= 0 && endIndex > 0) {
      final int srcSize = value.size();
      if (endIndex > srcSize) {
        final MutableBytes paddedAnswer = MutableBytes.create((int) length);
        if (sourceOffset < srcSize) {
          value.slice((int) sourceOffset, (int) (srcSize - sourceOffset)).copyTo(paddedAnswer, 0);
        }
        setUpdatedMemory(offset, paddedAnswer.copy());
      } else {
        setUpdatedMemory(offset, value.slice((int) sourceOffset, (int) length).copy());
      }
    }
  }

  private void setUpdatedMemoryRightAligned(
      final long offset, final long length, final Bytes value) {
    if (length > 0) {
      final int srcSize = value.size();
      if (length > srcSize) {
        final MutableBytes paddedAnswer = MutableBytes.create((int) length);
        if ((long) 0 < srcSize) {
          value.slice(0, srcSize).copyTo(paddedAnswer, (int) (length - srcSize));
        }
        setUpdatedMemory(offset, paddedAnswer.copy());
      } else {
        setUpdatedMemory(offset, value.slice(0, (int) length).copy());
      }
    }
  }

  private void setUpdatedMemory(final long offset, final Bytes value) {
    maybeUpdatedMemory = Optional.of(new MemoryEntry(offset, value));
  }

  /**
   * Storage was updated.
   *
   * @param storageAddress the storage address
   * @param value the value
   */
  public void storageWasUpdated(final UInt256 storageAddress, final Bytes value) {
    maybeUpdatedStorage = Optional.of(new StorageEntry(storageAddress, value));
  }

  /**
   * Accumulate a log.
   *
   * @param log The log to accumulate
   */
  public void addLog(final Log log) {
    logs.add(log);
  }

  /**
   * Accumulate logs.
   *
   * @param logs The logs to accumulate
   */
  public void addLogs(final List<Log> logs) {
    this.logs.addAll(logs);
  }

  /** Clear the accumulated logs. */
  public void clearLogs() {
    logs.clear();
  }

  /**
   * Return the accumulated logs.
   *
   * @return the accumulated logs
   */
  public List<Log> getLogs() {
    return logs;
  }

  /**
   * Increment the gas refund.
   *
   * @param amount The amount to increment the refund
   */
  public void incrementGasRefund(final long amount) {
    this.gasRefund += amount;
  }

  /** Clear the accumulated gas refund. */
  public void clearGasRefund() {
    gasRefund = 0L;
  }

  /**
   * Return the accumulated gas refund.
   *
   * @return accumulated gas refund
   */
  public long getGasRefund() {
    return gasRefund;
  }

  /**
   * Add recipient to the self-destruct set if not already present.
   *
   * @param address The recipient to self-destruct
   */
  public void addSelfDestruct(final Address address) {
    selfDestructs.add(address);
  }

  /**
   * Add addresses to the self-destruct set if they are not already present.
   *
   * @param addresses The addresses to self-destruct
   */
  public void addSelfDestructs(final Set<Address> addresses) {
    selfDestructs.addAll(addresses);
  }

  /** Removes all entries in the self-destruct set. */
  public void clearSelfDestructs() {
    selfDestructs.clear();
  }

  /**
   * Returns the self-destruct set.
   *
   * @return the self-destruct set
   */
  public Set<Address> getSelfDestructs() {
    return selfDestructs;
  }

  /**
   * Add recipient to the create set if not already present.
   *
   * @param address The recipient to create
   */
  public void addCreate(final Address address) {
    creates.add(address);
  }
  /**
   * Add addresses to the create set if they are not already present.
   *
   * @param addresses The addresses to create
   */
  public void addCreates(final Set<Address> addresses) {
    creates.addAll(addresses);
  }

  /** Removes all entries in the create set. */
  public void clearCreates() {
    creates.clear();
  }

  /**
   * Returns the create set.
   *
   * @return the create set
   */
  public Set<Address> getCreates() {
    return creates;
  }

  /**
   * Was the account at this address created in this transaction? (in any of the previously executed
   * message frames in this transaction).
   *
   * @param address the address to check
   * @return true if the account was created in any parent or prior message frame in this
   *     transaction. False if the account existed in the world state at the beginning of the
   *     transaction.
   */
  public boolean wasCreatedInTransaction(final Address address) {
    return creates.contains((address))
        || (parentMessageFrame != null && parentMessageFrame.wasCreatedInTransaction(address));
  }

  /**
   * Add refund to the refunds map if not already present.
   *
   * @param beneficiary the beneficiary of the refund.
   * @param amount the amount of the refund.
   */
  public void addRefund(final Address beneficiary, final Wei amount) {
    refunds.put(beneficiary, amount);
  }

  /**
   * Returns the refunds map.
   *
   * @return the refunds map
   */
  public Map<Address, Wei> getRefunds() {
    return refunds;
  }

  /**
   * "Warms up" the address as per EIP-2929
   *
   * @param address the address to warm up
   * @return true if the address was already warmed up
   */
  public boolean warmUpAddress(final Address address) {
    if (warmedUpAddresses.add(address)) {
      return parentMessageFrame != null && parentMessageFrame.isWarm(address);
    } else {
      return true;
    }
  }

  private boolean isWarm(final Address address) {
    MessageFrame frame = this;
    while (frame != null) {
      if (frame.warmedUpAddresses.contains(address)) {
        return true;
      }
      frame = frame.parentMessageFrame;
    }
    return false;
  }

  /**
   * "Warms up" the storage slot as per EIP-2929
   *
   * @param address the address whose storage is being warmed up
   * @param slot the slot being warmed up
   * @return true if the storage slot was already warmed up
   */
  public boolean warmUpStorage(final Address address, final Bytes32 slot) {
    if (warmedUpStorage.put(address, slot)) {
      return parentMessageFrame != null && parentMessageFrame.isWarm(address, slot);
    } else {
      return true;
    }
  }

  private boolean isWarm(final Address address, final Bytes32 slot) {
    MessageFrame frame = this;
    while (frame != null) {
      if (frame.warmedUpStorage.containsEntry(address, slot)) {
        return true;
      }
      frame = frame.parentMessageFrame;
    }
    return false;
  }

  /**
   * Merge warmed up fields.
   *
   * @param childFrame the child frame
   */
  public void mergeWarmedUpFields(final MessageFrame childFrame) {
    if (childFrame == this) {
      return;
    }

    warmedUpAddresses.addAll(childFrame.warmedUpAddresses);
    warmedUpStorage.putAll(childFrame.warmedUpStorage);
  }

  /**
   * Return the world state.
   *
   * @return the world state
   */
  public WorldUpdater getWorldUpdater() {
    return worldUpdater;
  }

  /**
   * Returns the message frame type.
   *
   * @return the message frame type
   */
  public Type getType() {
    return type;
  }

  /**
   * Returns the current execution state.
   *
   * @return the current execution state
   */
  public State getState() {
    return state;
  }

  /**
   * Sets the current execution state.
   *
   * @param state The new execution state
   */
  public void setState(final State state) {
    this.state = state;
  }

  /**
   * Returns the code currently being executed.
   *
   * @return the code currently being executed
   */
  public Code getCode() {
    return code;
  }

  /**
   * Returns the current input data.
   *
   * @return the current input data
   */
  public Bytes getInputData() {
    return inputData;
  }

  /**
   * Returns the recipient account recipient
   *
   * @return the callee account recipient
   */
  public Address getRecipientAddress() {
    return recipient;
  }

  /**
   * Returns the message stack depth.
   *
   * @return the message stack depth
   */
  public int getMessageStackDepth() {
    return depth;
  }

  /**
   * Returns the recipient that originated the message.
   *
   * @return the recipient that originated the message
   */
  public Address getOriginatorAddress() {
    return originator;
  }

  /**
   * Returns the recipient of the code currently executing.
   *
   * @return the recipient of the code currently executing
   */
  public Address getContractAddress() {
    return contract;
  }

  /**
   * Returns the current gas price.
   *
   * @return the current gas price
   */
  public Wei getGasPrice() {
    return gasPrice;
  }

  /**
   * Returns the recipient of the sender.
   *
   * @return the recipient of the sender
   */
  public Address getSenderAddress() {
    return sender;
  }

  /**
   * Returns the value being transferred.
   *
   * @return the value being transferred
   */
  public Wei getValue() {
    return value;
  }

  /**
   * Returns the apparent value being transferred.
   *
   * @return the apparent value being transferred
   */
  public Wei getApparentValue() {
    return apparentValue;
  }

  /**
   * Returns the current block header.
   *
   * @return the current block header
   */
  public BlockValues getBlockValues() {
    return blockValues;
  }

  /** Performs updates based on the message frame's execution. */
  public void notifyCompletion() {
    completer.accept(this);
  }

  /**
   * Returns the current message frame stack.
   *
   * @return the current message frame stack
   */
  public Deque<MessageFrame> getMessageFrameStack() {
    return messageFrameStack;
  }

  /**
   * Sets exceptional halt reason.
   *
   * @param exceptionalHaltReason the exceptional halt reason
   */
  public void setExceptionalHaltReason(
      final Optional<ExceptionalHaltReason> exceptionalHaltReason) {
    this.exceptionalHaltReason = exceptionalHaltReason;
  }

  /**
   * Gets exceptional halt reason.
   *
   * @return the exceptional halt reason
   */
  public Optional<ExceptionalHaltReason> getExceptionalHaltReason() {
    return exceptionalHaltReason;
  }

  /**
   * Returns the current miningBeneficiary (aka coinbase)
   *
   * @return the current mining beneficiary
   */
  public Address getMiningBeneficiary() {
    return miningBeneficiary;
  }

  /**
   * Gets block hash lookup.
   *
   * @return the block hash lookup
   */
  public Function<Long, Hash> getBlockHashLookup() {
    return blockHashLookup;
  }

  /**
   * Gets current operation.
   *
   * @return the current operation
   */
  public Operation getCurrentOperation() {
    return currentOperation;
  }

  /**
   * Gets max stack size.
   *
   * @return the max stack size
   */
  public int getMaxStackSize() {
    return maxStackSize;
  }

  /**
   * Gets context variable.
   *
   * @param <T> the type parameter
   * @param name the name
   * @return the context variable
   */
  @SuppressWarnings({"unchecked", "TypeParameterUnusedInFormals"})
  public <T> T getContextVariable(final String name) {
    return (T) contextVariables.get(name);
  }

  /**
   * Gets context variable.
   *
   * @param <T> the type parameter
   * @param name the name
   * @param defaultValue the default value
   * @return the context variable
   */
  @SuppressWarnings("unchecked")
  public <T> T getContextVariable(final String name, final T defaultValue) {
    return (T) contextVariables.getOrDefault(name, defaultValue);
  }

  /**
   * Has context variable.
   *
   * @param name the name
   * @return the boolean
   */
  public boolean hasContextVariable(final String name) {
    return contextVariables.containsKey(name);
  }

  /**
   * Sets current operation.
   *
   * @param currentOperation the current operation
   */
  public void setCurrentOperation(final Operation currentOperation) {
    this.currentOperation = currentOperation;
  }

  /**
   * Gets warmedUp Storage.
   *
   * @return the warmed up storage
   */
  public Multimap<Address, Bytes32> getWarmedUpStorage() {
    return warmedUpStorage;
  }

  /**
   * Gets maybe updated memory.
   *
   * @return the maybe updated memory
   */
  public Optional<MemoryEntry> getMaybeUpdatedMemory() {
    return maybeUpdatedMemory;
  }

  /**
   * Gets maybe updated storage.
   *
   * @return the maybe updated storage
   */
  public Optional<StorageEntry> getMaybeUpdatedStorage() {
    return maybeUpdatedStorage;
  }

  /**
   * Gets the transient storage value, including values from parent frames if not set
   *
   * @param accountAddress The address of the executing context
   * @param slot the slot to retrieve
   * @return the data value read
   */
  public Bytes32 getTransientStorageValue(final Address accountAddress, final Bytes32 slot) {
    Bytes32 data = transientStorage.get(accountAddress, slot);

    if (data != null) {
      return data;
    }

    if (parentMessageFrame != null) {
      data = parentMessageFrame.getTransientStorageValue(accountAddress, slot);
    }
    if (data == null) {
      data = Bytes32.ZERO;
    }
    transientStorage.put(accountAddress, slot, data);

    return data;
  }

  /**
   * Gets the transient storage value, including values from parent frames if not set
   *
   * @param accountAddress The address of the executing context
   * @param slot the slot to set
   * @param value the value to set in the transient store
   */
  public void setTransientStorageValue(
      final Address accountAddress, final Bytes32 slot, final Bytes32 value) {
    transientStorage.put(accountAddress, slot, value);
  }

  /** Writes the transient storage to the parent frame, if one exists */
  public void commitTransientStorage() {
    if (parentMessageFrame != null) {
      parentMessageFrame.transientStorage.putAll(transientStorage);
    }
  }

  /**
   * Accessor for versionedHashes, if present.
   *
   * @return optional list of hashes
   */
  public Optional<List<VersionedHash>> getVersionedHashes() {
    return versionedHashes;
  }

  /** Reset. */
  public void reset() {
    maybeUpdatedMemory = Optional.empty();
    maybeUpdatedStorage = Optional.empty();
  }

  /** The MessageFrame Builder. */
  public static class Builder {

    private Type type;
    private Deque<MessageFrame> messageFrameStack;
    private WorldUpdater worldUpdater;
    private Long initialGas;
    private Address address;
    private Address originator;
    private Address contract;
    private Wei gasPrice;
    private Bytes inputData;
    private Address sender;
    private Wei value;
    private Wei apparentValue;
    private Code code;
    private BlockValues blockValues;
    private int depth = -1;
    private int maxStackSize = DEFAULT_MAX_STACK_SIZE;
    private boolean isStatic = false;
    private Consumer<MessageFrame> completer;
    private Address miningBeneficiary;
    private Function<Long, Hash> blockHashLookup;
    private Map<String, Object> contextVariables;
    private Optional<Bytes> reason = Optional.empty();
    private Set<Address> accessListWarmAddresses = emptySet();
    private Multimap<Address, Bytes32> accessListWarmStorage = HashMultimap.create();

    private Optional<List<VersionedHash>> versionedHashes = Optional.empty();

    /**
     * Sets Type.
     *
     * @param type the type
     * @return the builder
     */
    public Builder type(final Type type) {
      this.type = type;
      return this;
    }

    /**
     * Sets Message frame stack.
     *
     * @param messageFrameStack the message frame stack
     * @return the builder
     */
    public Builder messageFrameStack(final Deque<MessageFrame> messageFrameStack) {
      this.messageFrameStack = messageFrameStack;
      return this;
    }

    /**
     * Sets World updater.
     *
     * @param worldUpdater the world updater
     * @return the builder
     */
    public Builder worldUpdater(final WorldUpdater worldUpdater) {
      this.worldUpdater = worldUpdater;
      return this;
    }

    /**
     * Sets Initial gas.
     *
     * @param initialGas the initial gas
     * @return the builder
     */
    public Builder initialGas(final long initialGas) {
      this.initialGas = initialGas;
      return this;
    }

    /**
     * Sets Address.
     *
     * @param address the address
     * @return the builder
     */
    public Builder address(final Address address) {
      this.address = address;
      return this;
    }

    /**
     * Sets Originator.
     *
     * @param originator the originator
     * @return the builder
     */
    public Builder originator(final Address originator) {
      this.originator = originator;
      return this;
    }

    /**
     * Sets Contract.
     *
     * @param contract the contract
     * @return the builder
     */
    public Builder contract(final Address contract) {
      this.contract = contract;
      return this;
    }

    /**
     * Sets Gas price.
     *
     * @param gasPrice the gas price
     * @return the builder
     */
    public Builder gasPrice(final Wei gasPrice) {
      this.gasPrice = gasPrice;
      return this;
    }

    /**
     * Sets Input data.
     *
     * @param inputData the input data
     * @return the builder
     */
    public Builder inputData(final Bytes inputData) {
      this.inputData = inputData;
      return this;
    }

    /**
     * Sets Sender address.
     *
     * @param sender the sender
     * @return the builder
     */
    public Builder sender(final Address sender) {
      this.sender = sender;
      return this;
    }

    /**
     * Sets Value.
     *
     * @param value the value
     * @return the builder
     */
    public Builder value(final Wei value) {
      this.value = value;
      return this;
    }

    /**
     * Sets Apparent value.
     *
     * @param apparentValue the apparent value
     * @return the builder
     */
    public Builder apparentValue(final Wei apparentValue) {
      this.apparentValue = apparentValue;
      return this;
    }

    /**
     * Sets Code.
     *
     * @param code the code
     * @return the builder
     */
    public Builder code(final Code code) {
      this.code = code;
      return this;
    }

    /**
     * Sets Block values.
     *
     * @param blockValues the block values
     * @return the builder
     */
    public Builder blockValues(final BlockValues blockValues) {
      this.blockValues = blockValues;
      return this;
    }

    /**
     * Sets Depth.
     *
     * @param depth the depth
     * @return the builder
     */
    public Builder depth(final int depth) {
      this.depth = depth;
      return this;
    }

    /**
     * Sets Is static.
     *
     * @param isStatic the is static
     * @return the builder
     */
    public Builder isStatic(final boolean isStatic) {
      this.isStatic = isStatic;
      return this;
    }

    /**
     * Sets Max stack size.
     *
     * @param maxStackSize the max stack size
     * @return the builder
     */
    public Builder maxStackSize(final int maxStackSize) {
      this.maxStackSize = maxStackSize;
      return this;
    }

    /**
     * Sets Completer.
     *
     * @param completer the completer
     * @return the builder
     */
    public Builder completer(final Consumer<MessageFrame> completer) {
      this.completer = completer;
      return this;
    }

    /**
     * Sets Mining beneficiary.
     *
     * @param miningBeneficiary the mining beneficiary
     * @return the builder
     */
    public Builder miningBeneficiary(final Address miningBeneficiary) {
      this.miningBeneficiary = miningBeneficiary;
      return this;
    }

    /**
     * Sets Block hash lookup.
     *
     * @param blockHashLookup the block hash lookup
     * @return the builder
     */
    public Builder blockHashLookup(final Function<Long, Hash> blockHashLookup) {
      this.blockHashLookup = blockHashLookup;
      return this;
    }

    /**
     * Sets Context variables.
     *
     * @param contextVariables the context variables
     * @return the builder
     */
    public Builder contextVariables(final Map<String, Object> contextVariables) {
      this.contextVariables = contextVariables;
      return this;
    }

    /**
     * Sets Reason.
     *
     * @param reason the reason
     * @return the builder
     */
    public Builder reason(final Bytes reason) {
      this.reason = Optional.ofNullable(reason);
      return this;
    }

    /**
     * Sets Access list warm addresses.
     *
     * @param accessListWarmAddresses the access list warm addresses
     * @return the builder
     */
    public Builder accessListWarmAddresses(final Set<Address> accessListWarmAddresses) {
      this.accessListWarmAddresses = accessListWarmAddresses;
      return this;
    }

    /**
     * Sets Access list warm storage.
     *
     * @param accessListWarmStorage the access list warm storage
     * @return the builder
     */
    public Builder accessListWarmStorage(final Multimap<Address, Bytes32> accessListWarmStorage) {
      this.accessListWarmStorage = accessListWarmStorage;
      return this;
    }

    /**
     * Sets versioned hashes list.
     *
     * @param versionedHashes the Optional list of versioned hashes
     * @return the builder
     */
    public Builder versionedHashes(final Optional<List<VersionedHash>> versionedHashes) {
      this.versionedHashes = versionedHashes;
      return this;
    }

    private void validate() {
      checkState(type != null, "Missing message frame type");
      checkState(messageFrameStack != null, "Missing message frame message frame stack");
      checkState(worldUpdater != null, "Missing message frame world updater");
      checkState(initialGas != null, "Missing message frame initial getGasRemaining");
      checkState(address != null, "Missing message frame recipient");
      checkState(originator != null, "Missing message frame originator");
      checkState(contract != null, "Missing message frame contract");
      checkState(gasPrice != null, "Missing message frame getGasRemaining price");
      checkState(inputData != null, "Missing message frame input data");
      checkState(sender != null, "Missing message frame sender");
      checkState(value != null, "Missing message frame value");
      checkState(apparentValue != null, "Missing message frame apparent value");
      checkState(code != null, "Missing message frame code");
      checkState(blockValues != null, "Missing message frame block header");
      checkState(depth > -1, "Missing message frame depth");
      checkState(completer != null, "Missing message frame completer");
      checkState(miningBeneficiary != null, "Missing mining beneficiary");
      checkState(blockHashLookup != null, "Missing block hash lookup");
      checkState(versionedHashes != null, "Missing optional versioned hashes");
    }

    /**
     * Build MessageFrame.
     *
     * @return instance of MessageFrame
     */
    public MessageFrame build() {
      validate();

      return new MessageFrame(
          type,
          messageFrameStack,
          worldUpdater,
          initialGas,
          address,
          originator,
          contract,
          gasPrice,
          inputData,
          sender,
          value,
          apparentValue,
          code,
          blockValues,
          depth,
          isStatic,
          completer,
          miningBeneficiary,
          blockHashLookup,
          contextVariables == null ? Map.of() : contextVariables,
          reason,
          maxStackSize,
          accessListWarmAddresses,
          accessListWarmStorage,
          versionedHashes);
    }
  }
}
