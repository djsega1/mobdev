package io.github.mobdev.data

import android.annotation.SuppressLint
import android.content.Context
import android.provider.ContactsContract

class ContactsRepository(private val context: Context) {

    @SuppressLint("Range")
    fun fetchAllContacts(): List<Contact> {
        val contactsMap = linkedMapOf<String, Contact>()

        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.CONTACT_ID,
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER
        )

        context.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            projection,
            null,
            null,
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " ASC"
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                val contactId = cursor.getString(
                    cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.CONTACT_ID)
                ) ?: continue

                val name = cursor.getString(
                    cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                )

                val phoneNumber = cursor.getString(
                    cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                )

                if (!contactsMap.containsKey(contactId)) {
                    contactsMap[contactId] = Contact(
                        id = contactId,
                        name = name,
                        phoneNumber = phoneNumber,
                        email = getEmailByContactId(contactId)
                    )
                }
            }
        }

        return contactsMap.values.toList()
    }

    @SuppressLint("Range")
    private fun getEmailByContactId(contactId: String): String? {
        val projection = arrayOf(ContactsContract.CommonDataKinds.Email.ADDRESS)
        val selection = "${ContactsContract.CommonDataKinds.Email.CONTACT_ID} = ?"
        val selectionArgs = arrayOf(contactId)

        context.contentResolver.query(
            ContactsContract.CommonDataKinds.Email.CONTENT_URI,
            projection,
            selection,
            selectionArgs,
            null
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                return cursor.getString(
                    cursor.getColumnIndex(ContactsContract.CommonDataKinds.Email.ADDRESS)
                )
            }
        }

        return null
    }
}