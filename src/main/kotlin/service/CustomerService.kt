package service

import org.jetbrains.exposed.sql.*
import db.DatabaseFactory.dbExec
import model.*
import model.param.UpsertCustomerRequest
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import plugin.json.fromJson
import plugin.json.fromStringToList
import plugin.json.toJson
import redis.clients.jedis.Jedis
import security.Hasher

class CustomerService {
    private val cache = Jedis("localhost", 6379)
    private val customerKey = "customer"
    private val allCustomersKey = "all_customers"

    suspend fun getAllCustomers(): List<Customer> {
        var listOfCustomers = cache.get(allCustomersKey)

        if (listOfCustomers == null) {
            println("\nValue associated with key [$allCustomersKey] is not in cache!\n")
            listOfCustomers = toJson(dbExec {
                Customers.selectAll()
                    .map { toCustomer(it) }
                    .toList()
            })
            cache.set(allCustomersKey, listOfCustomers)
        } else {
            print("\nValue associated with key [$allCustomersKey] is present in cache!\n")
        }

        return fromStringToList(listOfCustomers)
    }

    suspend fun getCustomer(id: Int): Customer? {
        val key = "$customerKey:$id"
        var customer = cache.get(key)

        if (customer == null) {
            println("\nValue associated with key [$key] is not in cache!\n")
            customer = toJson(dbExec {
                Customers.select {
                    (Customers.id eq id)
                }.map { toCustomer(it) }
                    .singleOrNull()
            })

            cache.set(key, customer)
            return fromJson( customer);
        } else {
            print("\nValue associated with key [$key] is present in cache!\n")
        }

        return fromJson(customer)
    }


    suspend fun getCustomerByEmail(email: String): Customer? {
            return dbExec {
                Customers.select {
                    (Customers.email eq email)
                }.map { toCustomer(it) }
                    .singleOrNull()
            }
    }

    suspend fun createCustomer(request: UpsertCustomerRequest): Customer {
        val existingCustomer: Customer? = getCustomerByEmail(request.email)

        if (existingCustomer != null) {
            println("Email already registered")
            throw EmailAlreadyRegisteredException("Email already registered")
        } else {
            var key = 0
            dbExec {
                key = (Customers.insert {
                    it[name] = request.name
                    it[address] = request.address
                    it[email] = request.email
                    it[dateOfBirth] = request.dateOfBirth
                    it[password] = Hasher.hash(request.password)
                    it[access] = request.access
                    it[delete] = request.delete
                } get Customers.id)
            }

            invalidateCache(allCustomersKey)
            val createdCustomer = getCustomer(key)
            return createdCustomer ?: throw NoSuchElementException("Customer not found after creation")
        }
    }

    suspend fun deleteCustomer(customerId: Int): Boolean = dbExec {

        // Delete all accounts associated with the specified customer
        val deletedAccounts = Accounts.deleteWhere {
            Accounts.customerId eq customerId
        }

        // Delete all loans associated with the specified customer
        val deletedLoans = Loans.deleteWhere {
            Loans.customerId eq customerId
        }

        // Delete the customer record now that all references are deleted
        val deletedCustomers = Customers.deleteWhere {
            id eq customerId
        }

        invalidateCache("$customerKey:$customerId")
        invalidateCache(allCustomersKey)

        // Return true if any accounts or customers were deleted
        deletedAccounts > 0 || deletedLoans > 0 || deletedCustomers > 0
    }

    suspend fun updateCustomer(id: Int, request: UpsertCustomerRequest): Customer? {
            dbExec {
                Customers.update({ Customers.id eq id }) {
                    it[name] = request.name
                    it[address] = request.address
                    it[dateOfBirth] = request.dateOfBirth
                    it[access] = request.access
                    it[delete] = request.delete
                }
            }
            invalidateCache(allCustomersKey)
            invalidateCache("$customerKey:$id")
            return getCustomer(id)
        }


    private fun toCustomer(row: ResultRow): Customer =
        Customer(
            id = row[Customers.id],
            name = row[Customers.name],
            address = row[Customers.address],
            email = row[Customers.email],
            dateOfBirth = row[Customers.dateOfBirth],
            password = row[Customers.password],
            access = row[Customers.access],
            delete = row[Customers.delete]
        )

    private fun invalidateCache(key: String) {
        cache.del(key)
        println("\nValue associated with key [$key] has been deleted from the cache\n")
    }
}