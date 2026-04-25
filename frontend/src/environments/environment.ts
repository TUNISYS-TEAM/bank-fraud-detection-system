export const environment = {
  production: false,
  apiUrl: 'http://localhost:8100/api',  
  forecastApiUrl: 'http://localhost:8003/api/v1',
  keycloak: {
    url: 'http://localhost:8180',        
    realm: 'fraud-detection',
    clientId: 'fraud-backend',
    clientSecret: 'oFQ9ky6RLeSVZyiXMdCkOGKJAxkCRB28'
  }
};