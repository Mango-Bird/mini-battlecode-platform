    function __freeze(value) {
        if (value && typeof value === 'object') { Object.values(value).forEach(__freeze); Object.freeze(value); }
        return value;
    }
    const action = __controlInvoke(__freeze(__controlRequest.observation));
    if (action && typeof action.then === 'function') throw new Error('async result prohibited');
    // Native runtime descriptor inspection. Arbitrary Proxy/code execution is NOT enabled by Java admission.
    function __dataKeys(object, keys) {
        if (!object || Object.getPrototypeOf(object) !== Object.prototype) return false;
        const descriptors = Object.getOwnPropertyDescriptors(object);
        return Reflect.ownKeys(descriptors).length === keys.length && keys.every(key =>
            descriptors[key] && 'value' in descriptors[key] && !descriptors[key].get && !descriptors[key].set);
    }
    if (!__dataKeys(action, ['pieceId','to']) || typeof action.pieceId !== 'string'
        || !__dataKeys(action.to, ['row','col']) || !Number.isInteger(action.to.row) || !Number.isInteger(action.to.col)) {
        __controlSend({executionId: __controlRequest.executionId, failure: 'INVALID_ACTION'});
    } else {
        __controlSend({executionId: __controlRequest.executionId, action: action});
    }
} catch (error) {
    __controlSend({executionId: __controlRequest.executionId, failure: 'BOT_RUNTIME_ERROR'});
}
