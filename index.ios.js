module.exports = {
  constants: {},
  scan,
  cancel,
}

function scan() {
  throw new Error('react-native-passport-reader is not supported on iOS')
}

function cancel() {
  throw new Error('react-native-passport-reader is not supported on iOS')
}
