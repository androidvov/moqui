/*
 * This Work is in the public domain and is provided on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied,
 * including, without limitation, any warranties or conditions of TITLE,
 * NON-INFRINGEMENT, MERCHANTABILITY, or FITNESS FOR A PARTICULAR PURPOSE.
 * You are solely responsible for determining the appropriateness of using
 * this Work and assume any risks associated with your use of this Work.
 *
 * This Work includes contributions authored by David E. Jones, not as a
 * "work for hire", who hereby disclaims any copyright to the same.
 */
package org.moqui.impl.context

import com.atomikos.icatch.jta.UserTransactionManager

import javax.transaction.Transaction
import javax.transaction.xa.XAResource
import javax.transaction.Synchronization
import javax.transaction.SystemException
import javax.transaction.UserTransaction
import javax.transaction.TransactionManager
import javax.transaction.Status
import javax.transaction.NotSupportedException
import javax.transaction.RollbackException
import javax.transaction.HeuristicMixedException
import javax.transaction.HeuristicRollbackException
import javax.transaction.InvalidTransactionException
import javax.naming.InitialContext
import javax.naming.NamingException
import javax.naming.Context
import java.sql.SQLException
import javax.sql.XAConnection
import java.sql.Connection

import org.moqui.context.TransactionException
import org.moqui.context.TransactionFacade

import org.slf4j.Logger
import org.slf4j.LoggerFactory

class TransactionFacadeImpl implements TransactionFacade {
    protected final static Logger logger = LoggerFactory.getLogger(TransactionFacadeImpl.class)

    protected final ExecutionContextFactoryImpl ecfi;

    protected UserTransaction ut
    protected TransactionManager tm

    private ThreadLocal<ArrayList<Exception>> transactionBeginStackList = new ThreadLocal<ArrayList<Exception>>()
    private ThreadLocal<ArrayList<RollbackInfo>> rollbackOnlyInfoStackList = new ThreadLocal<ArrayList<RollbackInfo>>()
    private ThreadLocal<ArrayList<Transaction>> suspendedTxStackList = new ThreadLocal<ArrayList<Transaction>>()

    TransactionFacadeImpl(ExecutionContextFactoryImpl ecfi) {
        this.ecfi = ecfi

        Node transactionFactory = this.ecfi.getConfXmlRoot()."transaction-facade"[0]."transaction-factory"[0]
        if (transactionFactory."@factory-type" == "jndi") {
            this.populateTransactionObjectsJndi()
        } else if (transactionFactory."@factory-type" == "internal") {
            UserTransactionManager utm = new UserTransactionManager()
            utm.init()
            this.ut = utm
            this.tm = utm
        } else {
            throw new IllegalArgumentException("Transaction factory type [${transactionFactory."@factory-type"}] not supported")
        }
    }

    void destroy() {
        // destroy ut, tm (just for internal/Atomikos; nothing for JNDI
        if (this.tm instanceof UserTransactionManager) {
            ((UserTransactionManager) this.tm).close()
            this.tm = null
            this.ut = null
        }
    }

    /** This is called to make sure all transactions, etc are closed for the thread.
     * It commits any active transactions, clears out internal data for the thread, etc.
     */
    void destroyAllInThread() {
        if (this.isTransactionInPlace()) {
            logger.warn("Thread ending with a transaction in place. Trying to commit.")
            this.commit()
        }

        if (suspendedTxStackList.get()) {
            int numSuspended = 0;
            for (Transaction tx in suspendedTxStackList.get()) {
                this.resume(tx)
                this.commit()
                numSuspended++
            }
            logger.warn("Cleaned up [" + numSuspended + "] suspended transactions.")
        }

        transactionBeginStackList.remove()
        rollbackOnlyInfoStackList.remove()
        suspendedTxStackList.remove()
    }

    protected ArrayList<Exception> getTransactionBeginStack() {
        ArrayList<Exception> list = (ArrayList<Exception>) transactionBeginStackList.get()
        if (!list) {
            list = new ArrayList<Exception>(10)
            list.add(null)
            transactionBeginStackList.set(list)
        }
        return list
    }
    protected ArrayList<RollbackInfo> getRollbackOnlyInfoStack() {
        ArrayList<RollbackInfo> list = (ArrayList<RollbackInfo>) rollbackOnlyInfoStackList.get()
        if (!list) {
            list = new ArrayList<RollbackInfo>(10)
            list.add(null)
            rollbackOnlyInfoStackList.set(list)
        }
        return list
    }
    protected ArrayList<Transaction> getSuspendedTxStack() {
        ArrayList<Transaction> list = (ArrayList<Transaction>) suspendedTxStackList.get()
        if (!list) {
            list = new ArrayList<Transaction>(10)
            list.add(null)
            suspendedTxStackList.set(list)
        }
        return list
    }


    /** @see org.moqui.context.TransactionFacade#getStatus() */
    int getStatus() {
        try {
            return ut.getStatus()
        } catch (SystemException e) {
            throw new TransactionException("System error, could not get transaction status", e)
        }
    }

    /** @see org.moqui.context.TransactionFacade#getStatusString() */
    String getStatusString() {
        int statusInt = getStatus()
        /*
         * javax.transaction.Status
         * STATUS_ACTIVE           0
         * STATUS_MARKED_ROLLBACK  1
         * STATUS_PREPARED         2
         * STATUS_COMMITTED        3
         * STATUS_ROLLEDBACK       4
         * STATUS_UNKNOWN          5
         * STATUS_NO_TRANSACTION   6
         * STATUS_PREPARING        7
         * STATUS_COMMITTING       8
         * STATUS_ROLLING_BACK     9
         */
        switch (statusInt) {
            case Status.STATUS_ACTIVE:
                return "Active (${statusInt})";
            case Status.STATUS_COMMITTED:
                return "Committed (${statusInt})";
            case Status.STATUS_COMMITTING:
                return "Committing (${statusInt})";
            case Status.STATUS_MARKED_ROLLBACK:
                return "Marked Rollback-Only (${statusInt})";
            case Status.STATUS_NO_TRANSACTION:
                return "No Transaction (${statusInt})";
            case Status.STATUS_PREPARED:
                return "Prepared (${statusInt})";
            case Status.STATUS_PREPARING:
                return "Preparing (${statusInt})";
            case Status.STATUS_ROLLEDBACK:
                return "Rolledback (${statusInt})";
            case Status.STATUS_ROLLING_BACK:
                return "Rolling Back (${statusInt})";
            case Status.STATUS_UNKNOWN:
                return "Status Unknown (${statusInt})";
            default:
                return "Not a valid status code (${statusInt})";
        }
    }

    /** @see org.moqui.context.TransactionFacade#isTransactionInPlace() */
    boolean isTransactionInPlace() {
        return getStatus() != Status.STATUS_NO_TRANSACTION
    }

    /** @see org.moqui.context.TransactionFacade#begin(Integer) */
    boolean begin(Integer timeout) {
        try {
            int currentStatus = ut.getStatus()
            if (currentStatus == Status.STATUS_ACTIVE) {
                // don't begin, and return false so caller knows we didn't
                return false
            } else if (currentStatus == Status.STATUS_MARKED_ROLLBACK) {
                if (getTransactionBeginStack()) {
                    logger.warn("Current transaction marked for rollback, so no transaction begun. This stack trace shows where the transaction began: ", getTransactionBeginStack().get(0))
                } else {
                    logger.warn("Current transaction marked for rollback, so no transaction begun (NOTE: No stack trace to show where transaction began).")
                }

                if (getRollbackOnlyInfoStack()) {
                    logger.warn("Current transaction marked for rollback, not beginning a new transaction. The rollback-only was set here: ", getRollbackOnlyInfoStack().get(0).rollbackLocation)
                    throw new TransactionException("Current transaction marked for rollback, so no transaction begun. The rollback was originally caused by: " + getRollbackOnlyInfoStack().get(0).causeMessage, getRollbackOnlyInfoStack().get(0).causeThrowable)
                } else {
                    return false
                }
            }

            this.beginWithTimeout(timeout)
            getTransactionBeginStack().set(0, new Exception("Tx Begin Placeholder"))

            return true
        } catch (NotSupportedException e) {
            throw new TransactionException("Could not begin transaction (could be a nesting problem)", e)
        } catch (SystemException e) {
            throw new TransactionException("Could not begin transaction", e)
        }
    }

    /** This is a synchronized method since we can only set the timeout for the whole system, and this will allow us to do it just for this transaction */
    private synchronized void beginWithTimeout(Integer timeout) {
        if (timeout) ut.setTransactionTimeout(timeout)
        ut.begin()
        if (timeout) ut.setTransactionTimeout(0)
    }

    /** @see org.moqui.context.TransactionFacade#commit(boolean) */
    void commit(boolean beganTransaction) { if (beganTransaction) this.commit() }

    /** @see org.moqui.context.TransactionFacade#commit() */
    void commit() {
        try {
            int status = ut.getStatus();
            if (status != Status.STATUS_NO_TRANSACTION && status != Status.STATUS_COMMITTING &&
                    status != Status.STATUS_COMMITTED && status != Status.STATUS_ROLLING_BACK &&
                    status != Status.STATUS_ROLLEDBACK) {
                ut.commit()
            } else {
                logger.warn("Not committing transaction because status is " + getStatusString())
            }
        } catch (RollbackException e) {
            RollbackInfo rollbackOnlyInfo = getRollbackOnlyInfoStack().get(0)
            if (rollbackOnlyInfo) {
                logger.warn("Could not commit transaction, was marked rollback-only. The rollback-only was set here: ", rollbackOnlyInfo.rollbackLocation)
                throw new TransactionException("Could not commit transaction, was marked rollback-only. The rollback was originally caused by: " + rollbackOnlyInfo.causeMessage, rollbackOnlyInfo.causeThrowable)
            } else {
                throw new TransactionException("Could not commit transaction, was rolled back instead (and we don't have a rollback-only cause)", e)
            }
        } catch (IllegalStateException e) {
            throw new TransactionException("Could not commit transaction", e)
        } catch (HeuristicMixedException e) {
            throw new TransactionException("Could not commit transaction", e)
        } catch (HeuristicRollbackException e) {
            throw new TransactionException("Could not commit transaction", e)
        } catch (SystemException e) {
            throw new TransactionException("Could not commit transaction", e)
        } finally {
            if (getRollbackOnlyInfoStack()) getRollbackOnlyInfoStack().set(0, null)
            if (getTransactionBeginStack()) getTransactionBeginStack().set(0, null)
        }
    }

    /** @see org.moqui.context.TransactionFacade#rollback(boolean, String, Throwable) */
    void rollback(boolean beganTransaction, String causeMessage, Throwable causeThrowable) {
        if (beganTransaction) {
            this.rollback(causeMessage, causeThrowable)
        } else {
            this.setRollbackOnly(causeMessage, causeThrowable)
        }
    }

    /** @see org.moqui.context.TransactionFacade#rollback(String, Throwable) */
    void rollback(String causeMessage, Throwable causeThrowable) {
        try {
            if (getStatus() == Status.STATUS_NO_TRANSACTION) {
                logger.warn("Transaction not rolled back, status is STATUS_NO_TRANSACTION")
                return
            }

            logger.warn("Transaction rollback. Here is the current location: ", new Exception("Rollback Locations"))
            logger.warn("Transaction rollback. The rollback was originally caused by: " + causeMessage, causeThrowable)

            ut.rollback()
        } catch (IllegalStateException e) {
            throw new TransactionException("Could not rollback transaction", e)
        } catch (SystemException e) {
            throw new TransactionException("Could not rollback transaction", e)
        } finally {
            // NOTE: should this really be in finally? maybe we only want to do this if there is a successful rollback
            // to avoid removing things that should still be there, or maybe here in finally it will match up the adds
            // and removes better
            if (getRollbackOnlyInfoStack()) getRollbackOnlyInfoStack().set(0, null)
            if (getTransactionBeginStack()) getTransactionBeginStack().set(0, null)
        }
    }

    /** @see org.moqui.context.TransactionFacade#setRollbackOnly(String, Throwable) */
    void setRollbackOnly(String causeMessage, Throwable causeThrowable) {
        try {
            int status = getStatus()
            if (status != Status.STATUS_NO_TRANSACTION) {
                if (status != Status.STATUS_MARKED_ROLLBACK) {
                    ut.setRollbackOnly()
                    // do this after setRollbackOnly so it only tracks it if rollback-only was actually set
                    getRollbackOnlyInfoStack().set(0, new RollbackInfo(causeMessage, causeThrowable, new Exception("Set rollback-only location")))
                }
            } else {
                logger.warn("Rollback only not set on current transaction, status is STATUS_NO_TRANSACTION")
            }
        } catch (IllegalStateException e) {
            throw new TransactionException("Could not set rollback only on current transaction", e)
        } catch (SystemException e) {
            throw new TransactionException("Could not set rollback only on current transaction", e)
        }
    }

    /** @see org.moqui.context.TransactionFacade#suspend() */
    Transaction suspend() {
        try {
            if (getStatus() == Status.STATUS_NO_TRANSACTION) {
                logger.warn("No transaction in place, so not suspending.")
                return null
            }
            Transaction tx = tm.suspend()
            // only do these after successful suspend
            getRollbackOnlyInfoStack().add(0, null)
            getTransactionBeginStack().add(0, null)
            getSuspendedTxStack().add(0, tx)
            return tx
        } catch (SystemException e) {
            throw new TransactionException("Could not suspend transaction", e)
        }
    }

    /** @see org.moqui.context.TransactionFacade#resume(Transaction) */
    void resume(Transaction parentTx) {
        if (parentTx == null) return
        try {
            tm.resume(parentTx)
            // only do these after successful resume
            getRollbackOnlyInfoStack().remove(0)
            getTransactionBeginStack().remove(0)
            getSuspendedTxStack().remove(0)
        } catch (InvalidTransactionException e) {
            throw new TransactionException("Could not resume transaction", e)
        } catch (SystemException e) {
            throw new TransactionException("Could not resume transaction", e)
        }
    }

    /** @see org.moqui.context.TransactionFacade#enlistConnection(XAConnection) */
    Connection enlistConnection(XAConnection con) {
        if (con == null) return null
        try {
            XAResource resource = con.getXAResource()
            this.enlistResource(resource)
            return con.getConnection()
        } catch (SQLException e) {
            throw new TransactionException("Could not enlist connection in transaction", e)
        }
    }

    /** @see org.moqui.context.TransactionFacade#enlistResource(XAResource) */
    void enlistResource(XAResource resource) {
        if (resource == null) return
        if (getStatus() != Status.STATUS_ACTIVE) {
            logger.warn("Not enlisting XAResource: transaction not ACTIVE", new Exception("Warning Location"))
            return
        }
        try {
            Transaction tx = tm.getTransaction()
            if (tx) {
                 tx.enlistResource(resource)
            } else {
                logger.warn("Not enlisting XAResource: transaction was null", new Exception("Warning Location"))
            }
        } catch (RollbackException e) {
            throw new TransactionException("Could not enlist XAResource in transaction", e)
        } catch (SystemException e) {
            throw new TransactionException("Could not enlist XAResource in transaction", e)
        }
    }

    /** @see org.moqui.context.TransactionFacade#registerSynchronization(Synchronization) */
    void registerSynchronization(Synchronization sync) {
        if (sync== null) return
        if (getStatus() != Status.STATUS_ACTIVE) {
            logger.warn("Not registering Synchronization: transaction not ACTIVE", new Exception("Warning Location"))
            return
        }
        try {
            Transaction tx = tm.getTransaction()
            if (tx) {
                 tx.registerSynchronization(sync)
            } else {
                logger.warn("Not registering Synchronization: transaction was null", new Exception("Warning Location"))
            }
        } catch (RollbackException e) {
            throw new TransactionException("Could not register Synchronization in transaction", e)
        } catch (SystemException e) {
            throw new TransactionException("Could not register Synchronization in transaction", e)
        }
    }

    static class RollbackInfo {
        String causeMessage
        /** A rollback is often done because of another error, this represents that error. */
        Throwable causeThrowable
        /** This is for a stack trace for where the rollback was actually called to help track it down more easily. */
        Exception rollbackLocation

        RollbackInfo(String causeMessage, Throwable causeThrowable, Exception rollbackLocation) {
            this.causeMessage = causeMessage
            this.causeThrowable = causeThrowable
            this.rollbackLocation = rollbackLocation
        }
    }

    // ========== Initialize/Populate Methods ==========

    void populateTransactionObjectsJndi() {
        Node transactionFactory = this.ecfi.getConfXmlRoot()."transaction-facade"[0]."transaction-factory"[0]
        String userTxJndiName = transactionFactory."@user-transaction-jndi-name"
        String txMgrJndiName = transactionFactory."@transaction-manager-jndi-name"

        Node serverJndi = this.ecfi.getConfXmlRoot()."transaction-facade"[0]."server-jndi"[0]

        try {
            InitialContext ic;
            if (serverJndi) {
                Hashtable<String, Object> h = new Hashtable<String, Object>()
                h.put(Context.INITIAL_CONTEXT_FACTORY, serverJndi."@initial-context-factory")
                h.put(Context.PROVIDER_URL, serverJndi."@context-provider-url")
                if (serverJndi."@url-pkg-prefixes") h.put(Context.URL_PKG_PREFIXES, serverJndi."@url-pkg-prefixes")
                if (serverJndi."@security-principal") h.put(Context.SECURITY_PRINCIPAL, serverJndi."@security-principal")
                if (serverJndi."@security-credentials") h.put(Context.SECURITY_CREDENTIALS, serverJndi."@security-credentials")
                ic = new InitialContext(h)
            } else {
                ic = new InitialContext()
            }

            this.ut = (UserTransaction) ic.lookup(userTxJndiName)
            this.tm = (TransactionManager) ic.lookup(txMgrJndiName)
        } catch (NamingException ne) {
            logger.error("Error while finding JNDI Transaction objects [${userTxJndiName}] and [${txMgrJndiName}] from server [${serverJndi ? serverJndi."@context-provider-url" : "default"}].", ne)
        }

        if (!this.ut) logger.error("Could not find UserTransaction with name [${userTxJndiName}] in JNDI server [${serverJndi ? serverJndi."@context-provider-url" : "default"}].")
        if (!this.tm) logger.error("Could not find TransactionManager with name [${txMgrJndiName}] in JNDI server [${serverJndi ? serverJndi."@context-provider-url" : "default"}].")
    }
}
