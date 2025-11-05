import axios from 'axios';

/**
 * Passkey credential info
 * Feature: Passkey MFA Support
 */
export interface PasskeyCredentialInfo {
  id: number;
  credentialName: string;
  createdAt: string;
  lastUsedAt: string | null;
}

/**
 * Passkey list response
 */
export interface PasskeyListResponse {
  passkeys: PasskeyCredentialInfo[];
  count: number;
}

/**
 * Service for Passkey/WebAuthn API operations
 * Feature: Passkey MFA Support
 */
class PasskeyService {
  private readonly baseUrl = '/api/passkey';

  /**
   * Get registration options for creating a new passkey
   */
  async getRegistrationOptions(): Promise<any> {
    const response = await axios.get(`${this.baseUrl}/register-options`);
    return response.data;
  }

  /**
   * Register a new passkey credential
   */
  async registerCredential(credentialName: string, credential: any): Promise<any> {
    const response = await axios.post(`${this.baseUrl}/register`, {
      credentialName,
      credential
    });
    return response.data;
  }

  /**
   * List all passkeys for the current user
   */
  async listPasskeys(): Promise<PasskeyListResponse> {
    const response = await axios.get<PasskeyListResponse>(`${this.baseUrl}/list`);
    return response.data;
  }

  /**
   * Delete a passkey
   */
  async deletePasskey(id: number): Promise<any> {
    const response = await axios.delete(`${this.baseUrl}/${id}`);
    return response.data;
  }

  /**
   * Start passkey registration flow using WebAuthn API
   */
  async startRegistration(credentialName: string): Promise<void> {
    try {
      // Get registration options from backend
      const options = await this.getRegistrationOptions();

      // Convert base64url strings to Uint8Array
      const challenge = this.base64urlToUint8Array(options.challenge);
      const userId = this.base64urlToUint8Array(options.user.id);

      // Prepare WebAuthn credential creation options
      const publicKeyOptions: PublicKeyCredentialCreationOptions = {
        challenge,
        rp: {
          name: options.rp.name,
          id: options.rp.id
        },
        user: {
          id: userId,
          name: options.user.name,
          displayName: options.user.displayName
        },
        pubKeyCredParams: options.pubKeyCredParams.map((param: any) => ({
          type: param.type,
          alg: param.alg
        })),
        timeout: options.timeout,
        authenticatorSelection: {
          authenticatorAttachment: options.authenticatorSelection.authenticatorAttachment,
          requireResidentKey: options.authenticatorSelection.requireResidentKey,
          residentKey: options.authenticatorSelection.residentKey,
          userVerification: options.authenticatorSelection.userVerification
        },
        attestation: options.attestation,
        excludeCredentials: options.excludeCredentials?.map((cred: any) => ({
          type: cred.type,
          id: this.base64urlToUint8Array(cred.id),
          transports: cred.transports
        }))
      };

      // Create credential using WebAuthn API
      const credential = await navigator.credentials.create({
        publicKey: publicKeyOptions
      }) as PublicKeyCredential;

      if (!credential) {
        throw new Error('Failed to create credential');
      }

      // Prepare credential response for backend
      const response = credential.response as AuthenticatorAttestationResponse;
      const credentialResponse = {
        id: credential.id,
        rawId: this.uint8ArrayToBase64url(new Uint8Array(credential.rawId)),
        type: credential.type,
        response: {
          clientDataJSON: this.uint8ArrayToBase64url(new Uint8Array(response.clientDataJSON)),
          attestationObject: this.uint8ArrayToBase64url(new Uint8Array(response.attestationObject)),
          transports: response.getTransports ? response.getTransports() : null,
          clientExtensionResults: null
        }
      };

      // Send credential to backend
      await this.registerCredential(credentialName, credentialResponse);

    } catch (error: any) {
      console.error('Passkey registration error:', error);
      throw new Error(error.message || 'Failed to register passkey');
    }
  }

  /**
   * Convert base64url string to Uint8Array
   */
  private base64urlToUint8Array(base64url: string): Uint8Array {
    // Add padding if needed
    const base64 = base64url.replace(/-/g, '+').replace(/_/g, '/');
    const padding = '='.repeat((4 - base64.length % 4) % 4);
    const base64Padded = base64 + padding;

    const binary = atob(base64Padded);
    const bytes = new Uint8Array(binary.length);
    for (let i = 0; i < binary.length; i++) {
      bytes[i] = binary.charCodeAt(i);
    }
    return bytes;
  }

  /**
   * Convert Uint8Array to base64url string
   */
  private uint8ArrayToBase64url(buffer: Uint8Array): string {
    let binary = '';
    const len = buffer.byteLength;
    for (let i = 0; i < len; i++) {
      binary += String.fromCharCode(buffer[i]);
    }
    const base64 = btoa(binary);
    return base64.replace(/\+/g, '-').replace(/\//g, '_').replace(/=/g, '');
  }
}

export default new PasskeyService();
