import { NativeEventEmitter, NativeModules } from 'react-native'

const { RNPassportReader } = NativeModules
const Emitter = new NativeEventEmitter(RNPassportReader)

const DATE_REGEX = /^\d{6}$/

const constants = RNPassportReader.getConstants()

module.exports = {
  ...RNPassportReader,
  constants,
  scan
}

async function scan ({ documentNumber, dateOfBirth, dateOfExpiry, quality = 1, onProgress = () => {} }) {
  assert(typeof documentNumber === 'string', 'expected string "documentNumber"')
  assert(isDate(dateOfBirth), 'expected string "dateOfBirth" in format "yyMMdd"')
  assert(isDate(dateOfExpiry), 'expected string "dateOfExpiry" in format "yyMMdd"')
  
  const listener = Emitter.addListener(constants.EVENT_NFC_PROGRESS, onProgress)

  try {
    return await RNPassportReader.scan({ documentNumber, dateOfBirth, dateOfExpiry, quality })
  } finally {
    listener.remove()
  }
}


function assert (statement, err) {
  if (!statement) {
    throw new Error(err || 'Assertion failed')
  }
}

function isDate (str) {
  return typeof str === 'string' && DATE_REGEX.test(str)
}
